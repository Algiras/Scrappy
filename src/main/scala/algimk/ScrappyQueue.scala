package algimk

import java.io.File
import java.nio.file.Files

import algimk.Scrappy.ScrappyDriver
import algimk.config.{Config, DriverConfig, ProxyConfig}
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Queue
import hammock.asynchttpclient.AsyncHttpClientInterpreter
import hammock.{HttpResponse, InterpTrans}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.decode
import io.circe.{Decoder, Encoder}
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import org.http4s.dsl.impl.Root
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes, Response, Status}
import org.joda.time.DateTime
import pureconfig.generic.auto._
import pureconfig.module.catseffect._

import scala.concurrent.duration._

object ScrappyQueue extends IOApp {
  implicit val interpreter: InterpTrans[IO] = AsyncHttpClientInterpreter.instance[IO]

  implicit val enqueueEncoder: Encoder[EnqueueRequest] = deriveEncoder
  implicit val enqueueDecoder: Decoder[EnqueueRequest] = deriveDecoder

  implicit val enqueueRequestEntityDecoder: EntityDecoder[IO, EnqueueRequest] = jsonOf[IO, EnqueueRequest]
  implicit val enqueueRequestEntityEncoder: EntityEncoder[IO, EnqueueRequest] = jsonEncoderOf[IO, EnqueueRequest]

  case class EnqueueRequest(url: String, callbackUrl: Option[String])
  case class EnqueueScrapeResult(request: EnqueueRequest, html: String, time: DateTime)

  private def informSubscriberAboutParsedPage(subscriberUrl: String, at: DateTime, parsedPageUrl: String, html: String): IO[HttpResponse] = {
    import hammock._
    import hammock.circe.implicits._

    Hammock.request[String](Method.POST,
      uri"$subscriberUrl".params("recorded_at" -> at.getMillis.toString, "url" -> parsedPageUrl),
      Map(),
      Some(html)
    ).exec[IO]
  }

  private def persistHtmlRecord(storeDirectory: String, link: String, html: String): IO[Unit] = IO.delay {
    ScrappyQueue.urlAsFile(link).getOrElse(throw new RuntimeException("Can't parse file name"))
  }.flatMap(fileName => FileSystem.writeFile(storeDirectory ++ fileName, html))

  private def parseUrl(driver: ScrappyDriver, url: String): IO[String] = {
    Scrappy.driver(driver).map(driver => (for {
      _ <- Scrappy.get(url)
      elm <- Scrappy.gerElementByCssSelector("html")
    } yield elm).run(driver)).use(
      _.flatMap(elOpt => {
        val element = IO.fromEither(elOpt.toRight(new RuntimeException("Element not found")))
        val body = element.map(_.getChildren("body").head).attempt.map(
          _.flatMap(el => Either.cond(el.innerHtml.trim.nonEmpty, (), new RuntimeException("No body found")))
        )

        body.rethrow.flatMap(_ => element.map(_.html))
      })
    )
  }

  private def createDirectoryIfNotExist(storeDirectory: String): IO[Any] = {
    IO.delay {
      val folderPath = new File(storeDirectory).toPath
      if (!Files.exists(folderPath)) {
        Files.createDirectory(folderPath)
      }
    }
  }

  private[algimk] def readProxies(fileNameOpt: Option[String]): IO[List[ProxyConfig]] = fileNameOpt.map(fileName =>
    FileSystem.readFile(fileName).map(content => decode[List[ProxyConfig]](content)).rethrow
  ).getOrElse(IO(List.empty[ProxyConfig]))

  private[algimk] def consumeLinkStreamAndProduceParseStream(linkQueue: Queue[IO, EnqueueRequest],
                                                             parseQueue: Queue[IO, EnqueueScrapeResult],
                                                             scrappyDriverQueue: Queue[IO, ScrappyDriver],
                                                             reportError: (Throwable, String) => IO[Unit]): Stream[IO, EnqueueScrapeResult] = {

    scrappyDriverQueue.dequeue.zip(linkQueue.dequeue).evalMap { case (driver, req) => for {
      parsedHtml <- parseUrl(driver, req.url).attempt
      time <- timer.clock.realTime(MILLISECONDS).map(new DateTime(_))
      result = parsedHtml.map(EnqueueScrapeResult(req, _, time))
      _ <- result.fold(
        reportError(_, s"Failure scrapping page ${req.url}").flatMap(_ => linkQueue.enqueue1(req)),
        parseQueue.enqueue1
      )
    } yield result
    }.collect { case Right(value) => value }
  }

  private[algimk] def consumeParseStream(parseQueue: Queue[IO, EnqueueScrapeResult],
                                         recorderUrls: List[String],
                                         storeDirectory: String): Stream[IO, List[HttpResponse]] = {
    parseQueue.dequeue.evalMap(parseReq => for {
      _ <- persistHtmlRecord(storeDirectory, s"${parseReq.time.getMillis}-${parseReq.request.url}", parseReq.html)
      responses <- (parseReq.request.callbackUrl.toList ++ recorderUrls).map(
        url => informSubscriberAboutParsedPage(url, parseReq.time, parseReq.request.url, parseReq.html)
      ).sequence
    } yield responses)
  }

  private[algimk] def combineLinkAndParseStreams(linkQueue: Queue[IO, EnqueueRequest],
                                                 parseQueue: Queue[IO, EnqueueScrapeResult],
                                                 recorderUrls: List[String],
                                                 storeDirectory: String,
                                                 scrappyDrivers: Queue[IO, ScrappyDriver],
                                                 errorReporter: (Throwable, String) => IO[Unit]): Stream[IO, Either[EnqueueScrapeResult, List[HttpResponse]]] = {

    Stream(
      consumeLinkStreamAndProduceParseStream(linkQueue, parseQueue, scrappyDrivers, errorReporter).map(Left(_)),
      consumeParseStream(parseQueue, recorderUrls, storeDirectory).map(Right(_))
    ).parJoin(2)
  }

  private[algimk] def urlAsFile(url: String): Option[String] = {
    val parsedUrl = url
      .replace('.', '_')
      .replace('/', '_')
      .replace(':', '_')
      .replaceAll("_+", "_")
      .trim

    if (parsedUrl.nonEmpty) {
      Some(parsedUrl ++ ".html")
    } else {
      None
    }
  }

  private[algimk] def enqueueScrappyDrivers(driverConfigs: List[DriverConfig], proxyConfigs: List[ProxyConfig], queue: Queue[IO, ScrappyDriver]): Stream[IO, Unit] = {
    lazy val repeatedDrivers: Stream[IO, DriverConfig] = Stream.emits(driverConfigs).repeat

    val queueProxies: IO[Stream[IO, Unit]] = ProxyConfig.getProxies.map(proxies => repeatedDrivers
      .zipWith(Stream.emits(proxies))((drv, prx) => ScrappyDriver(drv, prx))
      .evalMap(queue.enqueue1)
    )

    Stream.eval(queueProxies).flatten ++ Stream.fixedDelay[IO](15.minutes).flatMap(_ => Stream.eval[IO, Stream[IO, Unit]](queueProxies).flatten)
  }

  private object OptionalTokenParamMatcher extends OptionalQueryParamDecoderMatcher[String]("token")

  private[algimk] def server(linkQueue: Queue[IO, EnqueueRequest], port: Option[Int] = None, serverToken: Option[String] = None): BlazeServerBuilder[IO] = BlazeServerBuilder[IO]
    .bindHttp(port.getOrElse(0))
    .withHttpApp(HttpRoutes.of[IO] {
      case req@POST -> Root / "enqueue" :? OptionalTokenParamMatcher(token) =>
        if (serverToken.forall(tk => token.contains(tk))) {
          req.as[EnqueueRequest]
            .flatMap(body =>
              linkQueue.enqueue1(body).map(_ => Response(Status.Ok))
            )
        } else IO.pure(Response(Unauthorized))
    }.orNotFound)

  override def run(args: List[String]): IO[ExitCode] = for {
    config <- loadConfigF[IO, Config]
    logger <- Slf4jLogger.create[IO]
    proxies <- readProxies(config.proxyConfigFileName)
    linkQueue <- Queue.bounded[IO, EnqueueRequest](config.queueBounds.linkQueueBound)
    parseQueue <- Queue.bounded[IO, EnqueueScrapeResult](config.queueBounds.parseQueueBound)
    driverQueue <- Queue.unbounded[IO, ScrappyDriver]
    _ <- createDirectoryIfNotExist(config.storeDirectory)
    configuredServer = server(linkQueue, Some(config.http.port), config.token)
    populateDrivers = enqueueScrappyDrivers(config.browserDrivers, proxies, driverQueue)
    combineQueueStream = combineLinkAndParseStreams(
      linkQueue,
      parseQueue,
      config.subscribers,
      config.storeDirectory,
      driverQueue,
      (error, msg) => logger.error(error)(msg)
    )
    exitCode <- Stream(
      configuredServer.serve,
      combineQueueStream,
      populateDrivers
    ).parJoin(3).compile.drain.as(ExitCode.Success)
  } yield exitCode
}
