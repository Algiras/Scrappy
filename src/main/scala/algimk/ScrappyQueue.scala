package algimk

import java.io.File

import algimk.Scrappy.ScrappyDriver
import algimk.ScrappyServer.AuthUser
import algimk.config.{Config, DriverConfig, ProxyConfig}
import cats.effect.concurrent.Ref
import cats.effect.{Blocker, ExitCode, IO, IOApp}
import cats.instances.list._
import cats.syntax.functor._
import cats.syntax.monadError._
import cats.syntax.traverse._
import fs2.concurrent.Queue
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s._
import org.http4s.client.blaze._
import org.joda.time.DateTime
import pureconfig.generic.auto._
import pureconfig.module.catseffect._
import model._
import fs2.Stream
import org.http4s.client.Client
import io.chrisdavenport.fuuid.FUUID
import tsec.authentication.TSecBearerToken
import tsec.common.SecureRandomId
import tsec.passwordhashers.jca._

import scala.concurrent.duration._

object ScrappyQueue extends IOApp {

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

  def consumeLinkStreamAndProduceParseStream(linkStream: Stream[IO, EnqueueRetryRequest],
                                             linkEnqueue: EnqueueRetryRequest => IO[Unit],
                                             recordParseResult: EnqueueScrapeResult => IO[Unit],
                                             scrappyDriverQueue: Stream[IO, ScrappyDriver],
                                             reportError: (Throwable, String) => IO[Unit]): Stream[IO, EnqueueScrapeResult] = {
    scrappyDriverQueue.zip(linkStream).evalMap {
      case (driver, req) => for {
        parsedHtml <- parseUrl(driver, req.url).attempt
        time <- timer.clock.realTime(MILLISECONDS).map(new DateTime(_))
        result = parsedHtml.map(EnqueueScrapeResult(req, _, time))
        _ <- result.fold(
          req.retryCount match {
            case count if count < 1 => reportError(_, s"Failure scrapping page ${req.url} after retrying")
            case _ => reportError(_, s"Failure scrapping page ${req.url}").flatMap(_ => linkEnqueue(req.copy(retryCount = req.retryCount - 1)))
          },
          recordParseResult
        )
      } yield result
    }.collect { case Right(value) => value }
  }

  private def informSubscriberAboutParsedPage(client: Client[IO], subscriberUrl: String, parseReq: EnqueueScrapeResult): IO[Status] =
    for {
      uri <- IO.fromEither(Uri.fromString(subscriberUrl))
      status <- client.fetch[Status](Request[IO](
        Method.POST,
        uri.withQueryParam("url", parseReq.request.url).withQueryParam("recorded_at", parseReq.time.getMillis),
        body = Stream.emits(parseReq.html.getBytes().toSeq),
      ))(res => IO.pure(res.status))
    } yield status

  def consumeParseStream(client: Client[IO],
                         parseQueue: Stream[IO, EnqueueScrapeResult],
                         recordToLog: EnqueueScrapeResult => IO[Unit],
                         recorderUrls: List[String]): Stream[IO, List[Status]] =
    parseQueue.evalMap((parseReq: EnqueueScrapeResult) => for {
      _ <- recordToLog(parseReq)
      responses <- (parseReq.request.callbackUrl.toList ++ recorderUrls).map(
        informSubscriberAboutParsedPage(client, _, parseReq)
      ).sequence
    } yield responses)

  def enqueueScrappyDrivers(driverConfigs: List[DriverConfig], proxyConfigs: List[ProxyConfig], enqueueDriver: ScrappyDriver => IO[Unit]): Stream[IO, Unit] = {
    lazy val repeatedDrivers: Stream[IO, DriverConfig] = Stream.emits(driverConfigs).repeat

    val queueProxies: IO[Stream[IO, Unit]] = ProxyConfig.getProxyScrapeProxies.map(proxies =>
      repeatedDrivers
        .zipWith(Stream.emits(proxies))(ScrappyDriver(_, _))
        .evalMap(enqueueDriver)
    )

    Stream.eval(queueProxies).flatten ++ Stream.fixedDelay[IO](15.minutes).flatMap(
      _ => Stream.eval[IO, Stream[IO, Unit]](queueProxies).flatten
    )
  }

  override def run(args: List[String]): IO[ExitCode] = (for {
    blocker <- Blocker[IO]
    client <- BlazeClientBuilder[IO](blocker.blockingContext).resource
  } yield (blocker, client)).use {
    case (blocker, client) => for {
      config <- loadConfigF[IO, Config]
      logger <- Slf4jLogger.create[IO]
      proxies <- (ProxyConfig.readProxies(blocker, (config.proxyConfigFileName).map(new File(_).toPath)))
      linkQueue <- Queue.bounded[IO, EnqueueRetryRequest](config.queueBounds.linkQueueBound)
      parseQueue <- Queue.bounded[IO, EnqueueScrapeResult](config.queueBounds.parseQueueBound)
      driverQueue <- Queue.unbounded[IO, ScrappyDriver]
      _ <- FileSystem.createDirectoryIfNotExist(blocker, new File(config.storeDirectory).toPath)
      users <- config.users.map(usr => FUUID.randomFUUID[IO].flatMap(id => BCrypt.hashpw[IO](usr.password).map(id -> AuthUser(usr.username, _)))).sequence.map(_.toMap)
      tokens <- Ref.of[IO, Map[SecureRandomId, TSecBearerToken[FUUID]]](Map.empty[SecureRandomId, TSecBearerToken[FUUID]])
      configuredServer = ScrappyServer.create(
        recordLink = linkQueue.enqueue1,
        port = Some(config.http.port),
        users = users,
        recordingStream = FileSystem.streamRecordings(blocker, new File(config.storeDirectory).toPath),
        retryCount = PositiveNumber(3),
        secretKey = config.secretKey,
        bearerTokens = tokens
      )
      populateDrivers = enqueueScrappyDrivers(config.browserDrivers, proxies, driverQueue.enqueue1)
      linkStream = consumeLinkStreamAndProduceParseStream(linkQueue.dequeue, linkQueue.enqueue1, parseQueue.enqueue1, driverQueue.dequeue, (error, msg) => logger.error(error)(msg))
      parseStream = consumeParseStream(client, parseQueue.dequeue, FileSystem.persistToDisk("history", FUUID.randomFUUID[IO].map(_.show + ".html"), config.storeDirectory, blocker), config.subscribers)
      exitCode <- Stream(
        linkStream,
        parseStream,
        configuredServer.serve,
        populateDrivers
      ).parJoin(4).compile.drain.as(ExitCode.Success)
    } yield exitCode
  }
}
