package algimk

import java.io.File
import java.nio.file.Path

import algimk.Scrappy.ScrappyDriver
import algimk.config.{Config, DriverConfig, ProxyConfig}
import cats.Applicative
import cats.effect.{Blocker, ExitCode, IO, IOApp, Resource}
import cats.instances.list._
import cats.syntax.functor._
import cats.syntax.monadError._
import cats.syntax.traverse._
import fs2.Stream
import fs2.concurrent.Queue
import fs2.io.file._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.Decoder.Result
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder, HCursor, Json}
import org.http4s._
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import org.http4s.client.blaze._
import org.http4s.server.blaze.BlazeServerBuilder
import org.joda.time.DateTime
import pureconfig.generic.auto._
import pureconfig.module.catseffect._

import scala.concurrent.duration._

object ScrappyQueue extends IOApp {
  implicit val timeEncoder: Encoder[DateTime] = new Encoder[DateTime] {
    override def apply(a: DateTime): Json = Encoder[Long].apply(a.getMillis)
  }
  implicit val timeDecoder: Decoder[DateTime] = new Decoder[DateTime] {
    override def apply(c: HCursor): Result[DateTime] = Decoder[Long].apply(c).map(ml => new DateTime(ml))
  }

  implicit private val enqueueEncoder: Encoder[EnqueueRequest] = deriveEncoder
  implicit private val enqueueDecoder: Decoder[EnqueueRequest] = deriveDecoder

  implicit val recordingEncoder: Encoder[Recording] = deriveEncoder
  implicit val recordingDecoder: Decoder[Recording] = deriveDecoder

  implicit val enqueueRequestEntityDecoder: EntityDecoder[IO, EnqueueRequest] = jsonOf[IO, EnqueueRequest]
  implicit val enqueueRequestEntityEncoder: EntityEncoder[IO, EnqueueRequest] = jsonEncoderOf[IO, EnqueueRequest]

  implicit val recordingRequestEntityDecoder: EntityDecoder[IO, Recording] = jsonOf[IO, Recording]
  implicit val recordingRequestEntityEncoder: EntityEncoder[IO, Recording] = jsonEncoderOf[IO, Recording]


  case class EnqueueRequest(url: String, callbackUrl: Option[String])

  case class EnqueueScrapeResult(request: EnqueueRequest, html: String, time: DateTime)

  case class Recording(at: DateTime, html: String)

  private def informSubscriberAboutParsedPage(subscriberUrl: String, at: DateTime, parsedPageUrl: String, html: String): IO[Status] = {
    IO.fromEither(Uri.fromString(subscriberUrl)).flatMap(uri =>
      (for {
        blocker <- Blocker[IO]
        res <- BlazeClientBuilder[IO](blocker.blockingContext).resource.flatMap(client => Resource.liftF(client.fetch[Status](Request[IO](
          Method.POST,
          uri.withQueryParam("url", parsedPageUrl).withQueryParam("recorded_at", at.getMillis),
          body = Stream.emits(html.getBytes().toSeq),
        ))(res => IO.pure(res.status))))
      } yield res).use(IO.pure)
    )
  }

  private def persistHtmlRecord(storeDirectory: String, link: String, html: String): IO[Unit] = IO.delay {
    FileSystem.urlAsFile(link).getOrElse(throw new RuntimeException("Can't parse file name"))
  }.flatMap(fileName => Blocker[IO].use(FileSystem.writeFile(_, storeDirectory ++ fileName, html)))

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

  def consumeLinkStreamAndProduceParseStream(linkStream: Stream[IO, EnqueueRequest],
                                             linkEnqueue:  EnqueueRequest => IO[Unit],
                                             parseQueue: Queue[IO, EnqueueScrapeResult],
                                             scrappyDriverQueue: Stream[IO, ScrappyDriver],
                                             reportError: (Throwable, String) => IO[Unit]): Stream[IO, EnqueueScrapeResult] = {

    scrappyDriverQueue.zip(linkStream).evalMap { case (driver, req) => for {
      parsedHtml <- parseUrl(driver, req.url).attempt
      time <- timer.clock.realTime(MILLISECONDS).map(new DateTime(_))
      result = parsedHtml.map(EnqueueScrapeResult(req, _, time))
      _ <- result.fold(
        reportError(_, s"Failure scrapping page ${req.url}").flatMap(_ => linkEnqueue(req)),
        parseQueue.enqueue1
      )
    } yield result
    }.collect { case Right(value) => value }
  }

  def consumeParseStream(parseQueue: Stream[IO, EnqueueScrapeResult],
                         recorderUrls: List[String],
                         storeDirectory: String): Stream[IO, List[Status]] = {
    parseQueue.evalMap(parseReq => for {
      _ <- persistHtmlRecord(storeDirectory, s"${parseReq.time.getMillis}-${parseReq.request.url}", parseReq.html)
      responses <- (parseReq.request.callbackUrl.toList ++ recorderUrls).map(
        url => informSubscriberAboutParsedPage(url, parseReq.time, parseReq.request.url, parseReq.html)
      ).sequence
    } yield responses)
  }

  def combineLinkAndParseStreams(linkQueue: Queue[IO, EnqueueRequest],
                                 parseQueue: Queue[IO, EnqueueScrapeResult],
                                 recorderUrls: List[String],
                                 storeDirectory: String,
                                 scrappyDrivers: Stream[IO, ScrappyDriver],
                                 errorReporter: (Throwable, String) => IO[Unit]): Stream[IO, Either[EnqueueScrapeResult, List[Status]]] = {

    Stream(
      consumeLinkStreamAndProduceParseStream(linkQueue.dequeue, linkQueue.enqueue1, parseQueue, scrappyDrivers, errorReporter).map(Left(_)),
      consumeParseStream(parseQueue.dequeue, recorderUrls, storeDirectory).map(Right(_))
    ).parJoin(2)
  }

  def enqueueScrappyDrivers(driverConfigs: List[DriverConfig], proxyConfigs: List[ProxyConfig], queue: Queue[IO, ScrappyDriver]): Stream[IO, Unit] = {
    lazy val repeatedDrivers: Stream[IO, DriverConfig] = Stream.emits(driverConfigs).repeat

    val queueProxies: IO[Stream[IO, Unit]] = ProxyConfig.getProxyScrapeProxies.map(proxies => repeatedDrivers
      .zipWith(Stream.emits(proxies))((drv, prx) => ScrappyDriver(drv, prx))
      .evalMap(queue.enqueue1)
    )

    Stream.eval(queueProxies).flatten ++ Stream.fixedDelay[IO](15.minutes).flatMap(_ => Stream.eval[IO, Stream[IO, Unit]](queueProxies).flatten)
  }

  def server(linkQueue: Queue[IO, EnqueueRequest], blocker: Blocker, storagePath: Path, port: Option[Int] = None, serverToken: Option[String] = None): BlazeServerBuilder[IO] = {
    import org.http4s.dsl.io._
    import org.http4s.syntax.kleisli._

    object OptionalTokenParamMatcher extends OptionalQueryParamDecoderMatcher[String]("token")

    BlazeServerBuilder[IO]
      .bindHttp(port.getOrElse(0))
      .withHttpApp(HttpRoutes.of[IO] {
        case req@POST -> Root / "enqueue" :? OptionalTokenParamMatcher(token) =>
          if (serverToken.forall(tk => token.contains(tk))) {
            req.as[EnqueueRequest]
              .flatMap(body =>
                linkQueue.enqueue1(body).map(_ => Response(Status.Ok))
              )
          } else IO.pure(Response(Unauthorized))
        case GET -> Root / "replay" => Ok(streamRecordings(blocker, storagePath))
      }.orNotFound)
  }

  def streamRecordings(blocker: Blocker, path: Path): Stream[IO, Recording] = {
    directoryStream[IO](blocker, path)
      .evalMap((path: Path) => {
        val fileName = path.toFile.getName
        val fileContent = FileSystem.readFile(blocker, path.toString)

        Applicative[IO].map2(IO(new DateTime(fileName.split('-').head.toLong)), fileContent)(Recording)
      })
  }

  override def run(args: List[String]): IO[ExitCode] = for {
    config <- loadConfigF[IO, Config]
    logger <- Slf4jLogger.create[IO]
    proxies <- ProxyConfig.readProxies(config.proxyConfigFileName)
    linkQueue <- Queue.bounded[IO, EnqueueRequest](config.queueBounds.linkQueueBound)
    parseQueue <- Queue.bounded[IO, EnqueueScrapeResult](config.queueBounds.parseQueueBound)
    driverQueue <- Queue.unbounded[IO, ScrappyDriver]
    _ <- Blocker[IO].use(FileSystem.createDirectoryIfNotExist(_, config.storeDirectory))
    configuredServer <- Blocker[IO].use(bk => IO(server(linkQueue, bk, new File(config.storeDirectory).toPath, Some(config.http.port), config.token)))
    populateDrivers = enqueueScrappyDrivers(config.browserDrivers, proxies, driverQueue)
    combineQueueStream = combineLinkAndParseStreams(
      linkQueue,
      parseQueue,
      config.subscribers,
      config.storeDirectory,
      driverQueue.dequeue,
      (error, msg) => logger.error(error)(msg)
    )
    exitCode <- Stream(
      configuredServer.serve,
      combineQueueStream,
      populateDrivers
    ).parJoin(3).compile.drain.as(ExitCode.Success)
  } yield exitCode
}
