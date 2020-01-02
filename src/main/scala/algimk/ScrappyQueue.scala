package algimk

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.Files

import algimk.Scrappy.ScrappyDriver
import algimk.config.{Config, ProxyConfig}
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Queue
import hammock.apache.ApacheInterpreter
import hammock.{HttpResponse, InterpTrans}
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
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scala.concurrent.duration._
import scala.io.Source

object ScrappyQueue extends IOApp {
  implicit val interpreter: InterpTrans[IO] = ApacheInterpreter.instance[IO]

  case class EnqueueRequest(url: String, callbackUrl: Option[String])

  case class EnqueueScrapeResult(request: EnqueueRequest, html: String, time: DateTime)

  def urlAsFile(url: String): Option[String] = {
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

  def readProxies(fileNameOpt: Option[String]): IO[List[ProxyConfig]] = fileNameOpt.map(fileName =>
    readFile(fileName).map(content => decode[List[ProxyConfig]](content)).rethrow
  ).getOrElse(IO(List.empty[ProxyConfig]))


  override def run(args: List[String]): IO[ExitCode] = for {
    config <- loadConfigF[IO, Config]
    logger <- Slf4jLogger.create[IO]
    proxies <- readProxies(config.proxyConfigFileName)
    linkQueue <- Queue.bounded[IO, EnqueueRequest](config.queueBounds.linkQueueBound)
    parseQueue <- Queue.bounded[IO, EnqueueScrapeResult](config.queueBounds.parseQueueBound)
    _ <- createDirectoryIfNotExist(config.storeDirectory)
    configuredServer = server(linkQueue, Some(config.http.port), config.token)
    combineQueueStream = combineLinkAndParseStreams(
      linkQueue,
      parseQueue,
      config.subscribers,
      config.storeDirectory,
      config.browserDrivers.flatMap(ScrappyDriver(_, proxies).toList),
      (error, msg) => logger.error(error)(msg)
    )
    exitCode <- Stream(
      configuredServer.serve,
      combineQueueStream
    ).parJoin(2).compile.drain.as(ExitCode.Success)
  } yield exitCode

  private def createDirectoryIfNotExist(storeDirectory: String): IO[Any] = {
    IO.delay {
      val folderPath = new File(storeDirectory).toPath
      if (!Files.exists(folderPath)) {
        Files.createDirectory(folderPath)
      }
    }
  }

  implicit val enqueueEncoder: Encoder[EnqueueRequest] = deriveEncoder
  implicit val enqueueDecoder: Decoder[EnqueueRequest] = deriveDecoder

  implicit val enqueueRequestEntityDecoder: EntityDecoder[IO, EnqueueRequest] = jsonOf[IO, EnqueueRequest]
  implicit val enqueueRequestEntityEncoder: EntityEncoder[IO, EnqueueRequest] = jsonEncoderOf[IO, EnqueueRequest]

  object OptionalTokenParamMatcher extends OptionalQueryParamDecoderMatcher[String]("token")

  def server(linkQueue: Queue[IO, EnqueueRequest], port: Option[Int] = None, serverToken: Option[String] = None): BlazeServerBuilder[IO] = BlazeServerBuilder[IO]
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

  def combineLinkAndParseStreams(linkQueue: Queue[IO, EnqueueRequest],
                                 parseQueue: Queue[IO, EnqueueScrapeResult],
                                 recorderUrls: List[String],
                                 storeDirectory: String,
                                 scrappyDrivers: List[ScrappyDriver],
                                 errorReporter: (Throwable, String) => IO[Unit]): Stream[IO, Either[EnqueueScrapeResult, List[HttpResponse]]] = {
    Stream(
      consumeLinkStreamAndProduceParseStream(linkQueue, parseQueue, scrappyDrivers, errorReporter).map(Left(_)),
      consumeParseStream(parseQueue, recorderUrls, storeDirectory).map(Right(_))
    ).parJoin(2)
  }

  private[algimk] def consumeLinkStreamAndProduceParseStream(linkQueue: Queue[IO, EnqueueRequest],
                                                             parseQueue: Queue[IO, EnqueueScrapeResult],
                                                             scrappyDrivers: List[ScrappyDriver],
                                                             reportError: (Throwable, String) => IO[Unit]): Stream[IO, EnqueueScrapeResult] = {
    lazy val drivers: Stream[IO, ScrappyDriver] = Stream.emits[IO, ScrappyDriver](scrappyDrivers) ++ drivers

    drivers.zip(linkQueue.dequeue).evalMap { case (driver, req) => for {
      htmlE <- Scrappy.driver(driver).map(driver => (for {
        _ <- Scrappy.get(req.url)
        elm <- Scrappy.gerElementByCssSelector("html")
      } yield elm).run(driver)).use(
        _.flatMap(elOpt => {
          val element = IO(elOpt.get).onError { case _ => IO.raiseError(new RuntimeException("Element not found")) }
          val body = element.map(_.getChildren("body").head).attempt.map(
            _.flatMap(el => Either.cond(el.innerHtml.trim.nonEmpty, (), new RuntimeException("No body found")))
          )

          body.rethrow.flatMap(_ => element.map(_.html))
        })
      ).attempt
      time <- timer.clock.realTime(MILLISECONDS).map(new DateTime(_))
      result = htmlE.map(EnqueueScrapeResult(req, _, time))
      _ <- result.fold(
        reportError(_, s"Failure scrapping page ${req.url}").flatMap(_ => linkQueue.enqueue1(req)),
        parseQueue.enqueue1
      )
    } yield result
    }.collect { case Right(value) => value }
  }

  private[algimk] def consumeParseStream(parseQueue: Queue[IO, EnqueueScrapeResult], recorderUrls: List[String], storeDirectory: String): Stream[IO, List[HttpResponse]] = {
    import hammock._
    import hammock.circe.implicits._

    parseQueue.dequeue.evalMap(parseReq => for {
      _ <- writeFile(storeDirectory, s"${parseReq.time.getMillis}-${parseReq.request.url}", parseReq.html)
      responses <- (parseReq.request.callbackUrl.toList ++ recorderUrls).map(
        url => Hammock.request[String](
          Method.POST,
          uri"$url".params("recorded_at" -> parseReq.time.getMillis.toString, "url" -> parseReq.request.url),
          Map(),
          Some(parseReq.html)
        ).exec[IO]
      ).sequence
    } yield responses)
  }

  private def writeFile(storeDirectory: String, link: String, html: String): IO[Unit] = Resource.make(IO.delay {
    val fileName = ScrappyQueue.urlAsFile(link).getOrElse(throw new RuntimeException("Can't parse file name"))
    new BufferedWriter(new FileWriter(storeDirectory ++ fileName))
  })(bf => IO(bf.close())).use(bf => IO(bf.write(html)))

  private def readFile(fileName: String): IO[String] = {
    IO(Source.fromFile(fileName)).bracket(
      bf => IO(bf.getLines().mkString(""))
    )(bf => IO(bf.close()))
  }
}
