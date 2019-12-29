package algimk

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.Files

import algimk.Scrappy.{Firefox, ScrappyDriver}
import algimk.config.Config
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Queue
import hammock.InterpTrans
import hammock.apache.ApacheInterpreter
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
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

  override def run(args: List[String]): IO[ExitCode] = for {
    config <- loadConfigF[IO, Config]
    linkQueue <- Queue.bounded[IO, EnqueueRequest](config.queueBounds.linkQueueBound)
    parseQueue <- Queue.bounded[IO, EnqueueScrapeResult](config.queueBounds.parseQueueBound)
    _ <- createDirectoryIfNotExist(config.storeDirectory)
    configuredServer = server(linkQueue, Some(config.http.port), config.token)
    combineQueueStream = combineLinkAndParseStreams(linkQueue, parseQueue, config.subscribers, config.storeDirectory, Firefox(config.drivers.firefox))
    exitCode <- Stream(
      configuredServer.serve,
      combineQueueStream
    ).parJoin(2).compile.drain.as(ExitCode.Success)
  } yield exitCode

  private def createDirectoryIfNotExist(storeDirectory: String) = {
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
                                 scrappyDriver: ScrappyDriver): Stream[IO, Unit] = {
    Stream(
      consumeLinkStreamAndProduceParseStream(linkQueue, parseQueue, scrappyDriver),
      consumeParseStream(parseQueue, recorderUrls, storeDirectory)
    ).parJoin(2)
  }

  private[algimk] def consumeLinkStreamAndProduceParseStream(linkQueue: Queue[IO, EnqueueRequest],
                                                     parseQueue: Queue[IO, EnqueueScrapeResult],
                                                     scrappyDriver: ScrappyDriver): Stream[IO, Unit] = {
    linkQueue.dequeue.evalMap(req => for {
      html <- Scrappy.driver(scrappyDriver).map(driver => (for {
        _ <- Scrappy.get(req.url)
        elm <- Scrappy.gerElementsByCssSelector("html")
      } yield elm).run(driver)).use(_.map(el => s"<html>${el.head.html}</html>"))
      time <- timer.clock.realTime(MILLISECONDS).map(new DateTime(_))
      _ <- parseQueue.enqueue1(EnqueueScrapeResult(req, html, time))
    } yield ())
  }

  private[algimk] def consumeParseStream(parseQueue: Queue[IO, EnqueueScrapeResult], recorderUrls: List[String], storeDirectory: String): Stream[IO, Unit] = {
    import hammock._
    import hammock.circe.implicits._

    parseQueue.dequeue.evalMap(parseReq => for {
      _ <- writeFile(storeDirectory, s"${parseReq.time.getMillis}-${parseReq.request.url}", parseReq.html)
      _ <- (parseReq.request.callbackUrl.toList ++ recorderUrls).map(
        url => Hammock.request[String](
          Method.POST,
          uri"$url".params("recorded_at" -> parseReq.time.getMillis.toString, "url" -> parseReq.request.url),
          Map(),
          Some(parseReq.html)
        ).exec[IO]
      ).sequence
    } yield ())
  }

  private def writeFile(storeDirectory: String, link: String, html: String): IO[Unit] = Resource.make(IO.delay {
    val fileName = ScrappyQueue.urlAsFile(link).getOrElse(throw new RuntimeException("Can't parse file name"))
    new BufferedWriter(new FileWriter(storeDirectory ++ fileName))
  })(bf => IO(bf.close())).use(bf => IO(bf.write(html)))
}
