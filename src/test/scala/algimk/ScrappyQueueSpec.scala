package algimk

import java.io.{BufferedReader, File, FileReader}

import algimk.Scrappy.ScrappyDriver
import ScrappyQueue._
import model._
import cats.effect.concurrent.Ref
import cats.effect.{Blocker, ContextShift, IO, Resource, Timer}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.{InspectableQueue, Queue, SignallingRef}
import org.http4s.dsl.impl.QueryParamDecoderMatcher
import org.http4s.server.Server
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.util.CaseInsensitiveString
import org.http4s.{server => _, _}
import org.joda.time.DateTime
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Spec
import org.specs2.specification.Scope
import org.http4s.client.blaze._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import jawnfs2.Absorbable._
import jawnfs2._
import io.circe.parser.decode
import cats.effect.IO
import io.chrisdavenport.fuuid.FUUID
import org.http4s.client.Client

class ScrappyQueueSpec(implicit val executionContext: ExecutionContext) extends Spec {
  val storeDirectory = "testPageStorage/"

  implicit val contextShift: ContextShift[IO] = IO.contextShift(executionContext)
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val jawnFacade = new io.circe.jawn.CirceSupportParser(None, allowDuplicateKeys = false).facade

  "ScrappyQueue" should {
    "record a page in scrapping queue" in new Context {
      val url = "http://www.google.com"

      val testCase: Resource[IO, MatchResult[String]] = for {
        urlQueue <- testQueue[EnqueueRetryRequest]
        historyName <- givenId
        server <- buildServerResource(historyName, urlQueue.enqueue1)
        _ <- enqueueRequest(server.baseUri.renderString, EnqueueRequest(url, None))
        urlQueueHead <- waitForFirstEntryInStream(urlQueue.dequeue, 1.second)
      } yield urlQueueHead.url must_=== url

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "use proxies when specified" in new Context {
      val testCase: Resource[IO, MatchResult[String]] = for {
        blocker <- Blocker[IO]
        client <- BlazeClientBuilder[IO](blocker.blockingContext).resource
        driverQueue <- Resource.liftF(Queue.unbounded[IO, ScrappyDriver])
        prx <- FakeServer.proxy
        drivers <- FakeServer.driverLocations.map {
          case (drv, _) => drv.map(d => driverQueue.enqueue1(ScrappyDriver(d, prx)))
        }
        _ <- Resource.liftF(drivers.sequence)
        historyName <- givenId
        pongUrl <- FakeServer.pongServer(_.headers.get(CaseInsensitiveString("Host")).get.value)
        requestRes <- fetchContent(pongUrl)
        urlQueue <- testQueue[EnqueueRetryRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        stream = combineLinkAndParseStreams(blocker, client, urlQueue, parseQueue, _ => IO.unit, List.empty, storeDirectory, driverQueue.dequeue, (_, _) => IO.unit)
        server <- buildServerResource(historyName, urlQueue.enqueue1)
        _ <- enqueueRequest(server.baseUri.renderString, EnqueueRequest(pongUrl, None))
        scrapeResult <- waitForFirstEntryInStream(stream, 10000.seconds).map(_.left.get)
      } yield scrapeResult.html must not(contain(requestRes))

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "reject queue request if it's not authorized and server provides a token" in new Context {
      val testCase: Resource[IO, Unit] = for {
        urlQueue <- testQueue[EnqueueRetryRequest]
        historyName <- givenId
        server <- buildServerResource(historyName, urlQueue.enqueue1, serverToken = Some("random-token"))
        req <- enqueueRequest(server.baseUri.renderString, EnqueueRequest("http://www.google.com", None)).attempt
      } yield req.map(_.code) must beRight(401)

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "allow queue request if it's authorized and server provides a token that matches" in new Context {
      val testCase: Resource[IO, Unit] = for {
        urlQueue <- testQueue[EnqueueRetryRequest]
        historyName <- givenId
        server <- buildServerResource(historyName, urlQueue.enqueue1, serverToken = Some("random-token"))
        req <- enqueueRequest(server.baseUri.renderString, EnqueueRequest("http://www.google.com", None), Some("random-token")).attempt
      } yield req.map(_.code) must beRight(200)

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "record full page scrape result" in new Context {
      val testCase: Resource[IO, Unit] = for {
        blocker <- Blocker[IO]
        drv <- defaultDrivers
        urlQueue <- testQueue[EnqueueRetryRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        stream = consumeLinkStreamAndProduceParseStream(urlQueue.dequeue, urlQueue.enqueue1(_), parseQueue.enqueue1, drv, (_, _) => IO.unit)
        urlToScrape <- FakeServer.givenFakeServer.map(srv => srv.url.renderString ++ "index.html")
        _ <- Resource.liftF(urlQueue.enqueue1(EnqueueRetryRequest(urlToScrape, None, 1)))
        res <- waitForFirstEntryInStream(stream, 20.seconds)
        _ <- matchesIndexContent(res.html)
      } yield ()

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "provide historical replay of previously recorded pages" in new Context {
      def requestResult(ct: ExecutionContext, uri: Uri): Resource[IO, Stream[IO, Recording]] = BlazeClientBuilder[IO](ct).resource.map(
        _.stream(Request[IO](Method.GET, uri)).flatMap(res => res.body.chunks.parseJsonStream)
          .flatMap(j => Stream.eval(IO(Recording.recordingDecoder.decodeJson(j)).rethrow))
      )

      val testCase: Resource[IO, Unit] = for {
        blocker <- Blocker[IO]
        drv <- defaultDrivers
        historyName <- givenId
        urlQueue <- testQueue[EnqueueRetryRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        basePath <- buildServerResource(historyName, urlQueue.enqueue1).map(_.baseUri)
        client <- BlazeClientBuilder[IO](blocker.blockingContext).resource
        stream = combineLinkAndParseStreams(blocker, client, urlQueue, parseQueue, FileSystem.persistToDisk(historyName, storeDirectory, blocker), List.empty, storeDirectory, drv, (_, _) => IO.raiseError(new RuntimeException("Failure to parse")))
        urlToScrape1 <- FakeServer.givenFakeServer.map(srv => srv.url.renderString ++ "index.html")
        urlToScrape2 <- FakeServer.givenFakeServer.map(srv => srv.url.renderString ++ "index.html")
        _ <- Resource.liftF(urlQueue.enqueue1(EnqueueRetryRequest(urlToScrape1, None, 1)))
        _ <- Resource.liftF(urlQueue.enqueue1(EnqueueRetryRequest(urlToScrape2, None, 1)))
        recordedRes <- Resource.liftF(stream.take(4).compile.to[List].map(_.collect { case Left(value) => value }.map(r => Recording(r.time, None, r.request.url, r.html.split('\n').mkString("")))).timeout(1000.seconds))
        stm <- requestResult(blocker.blockingContext, basePath / "replay")
        suppliedRes <- Resource.liftF(stm.take(2).compile.toList)
        _ <- deleteRecordedFiles(historyName)
      } yield recordedRes must containAnyOf(suppliedRes)

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "consider empty page to be a scrape failure" in new Context {
      val testCase: Resource[IO, Unit] = for {
        drv <- defaultDrivers
        urlQueue <- testQueue[EnqueueRetryRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        stream = consumeLinkStreamAndProduceParseStream(urlQueue.dequeue, urlQueue.enqueue1, parseQueue.enqueue1, drv, (_, _) => IO.unit)
        urlToScrape <- FakeServer.givenFakeServer.map(srv => srv.url.renderString ++ "empty.html")
        _ <- Resource.liftF(urlQueue.enqueue1(EnqueueRetryRequest(urlToScrape, None, 1)))
        res <- waitForFirstEntryInStream(stream, 10.seconds).attempt
      } yield res must beLeft

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "on scrape failure report error and continue working" in new Context {
      val testCase: Resource[IO, Unit] = for {
        drv <- defaultDrivers
        urlQueue <- testQueue[EnqueueRetryRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        ref <- Resource.liftF(Ref.of[IO, Either[String, Throwable]](Left("Was not called")))
        stream = consumeLinkStreamAndProduceParseStream(urlQueue.dequeue, urlQueue.enqueue1, parseQueue.enqueue1, drv, (error, _) => ref.set(Right(error)))
        _ <- Resource.liftF(urlQueue.enqueue1(EnqueueRetryRequest("https://127.0.0.1", None, 1)))
        res <- waitForFirstEntryInStream(stream, 20.seconds).attempt
        err <- Resource.liftF(ref.get)
        _ <- Resource.liftF(IO(err must beRight))
      } yield res must beLeft

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "on scrape failure, recover by retry" in new Context {
      val testCase: Resource[IO, Unit] = for {
        drv <- defaultDrivers
        urlQueue <- testQueue[EnqueueRetryRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        ref <- Resource.liftF(Ref.of[IO, Either[String, Throwable]](Left("Was not called")))
        stream = consumeLinkStreamAndProduceParseStream(urlQueue.dequeue, urlQueue.enqueue1, parseQueue.enqueue1, drv, (error, _) => ref.set(Right(error)))
        _ <- Resource.liftF(urlQueue.enqueue1(EnqueueRetryRequest("https://127.0.0.1", None, 1)))
        res <- waitForFirstEntryInStream(stream, 20.seconds).attempt
        err <- Resource.liftF(ref.get)
        _ <- Resource.liftF(IO(err must beRight))
      } yield res must beLeft

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "on scrape failure, retry up to fixed amount of times" in new Context {
      val errors = List(
        "Failure scrapping page https://127.0.0.1",
        "Failure scrapping page https://127.0.0.1 after retrying",
      )

      val testCase: Resource[IO, Unit] = for {
        drv <- defaultDrivers
        resultQueue <- testQueue[EnqueueRetryRequest]
        logQueue <- testQueue[String]
        urlQueue <- Resource.liftF(IO(Stream(List(
          EnqueueRetryRequest("https://127.0.0.1", None, 1),
          EnqueueRetryRequest("https://127.0.0.1", None, 0)
        ) : _*).covary[IO]))
        stream = consumeLinkStreamAndProduceParseStream(urlQueue, resultQueue.enqueue1, _ => IO.unit, drv, (_, text) => logQueue.enqueue1(text))
        _ <- Resource.liftF(stream.interruptWhen(logQueue.size.map(_ == 2)).compile.drain.timeout(20.seconds))
        enqueueResult <- Resource.liftF(resultQueue.dequeue.take(1).compile.toList)
        enqueueLog <- Resource.liftF(logQueue.dequeue.take(2).compile.toList)
        _ <- Resource.liftF(IO(enqueueLog must_=== errors))
      } yield enqueueResult must_=== List(EnqueueRetryRequest("https://127.0.0.1", None, 0))

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "write file with information retrieved from scrape for historical storage" in new Context {
      val url = "http://www.google.com"
      val fileContentText = "html"

      val testCase: Resource[IO, Unit] = for {
        blocker <- Blocker[IO]
        parseQueue <- testQueue[EnqueueScrapeResult]
        historyName <- givenId
        client <- BlazeClientBuilder[IO](blocker.blockingContext).resource
        stream = consumeParseStream(client, parseQueue.dequeue, FileSystem.persistToDisk(historyName, storeDirectory, blocker), List.empty)
        _ <- Resource.liftF(parseQueue.enqueue1(EnqueueScrapeResult(EnqueueRetryRequest(url, None, 1), fileContentText, new DateTime())))
        _ <- waitForFirstEntryInStream(stream, 1.second)
        fileContent <- readFileContent(url)
        _ <- Resource.liftF(IO(fileContentText must_=== fileContent))
        _ <- deleteRecordedFiles(historyName)
      } yield ()

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "specify application port" in new Context {
      val url = "http://www.google.com"
      val port = 8090

      val testCase: Resource[IO, Unit] = for {
        urlQueue <- testQueue[EnqueueRetryRequest]
        historyName <- givenId
        _ <- buildServerResource(historyName, urlQueue.enqueue1, Some(port))
        _ <- enqueueRequest(s"http://localhost:${port}/", EnqueueRequest(url, None))
      } yield ()

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "specify external recorder urls" in new Context {
      val url = "http://www.google.com"
      val html = "html"
      val date = new DateTime()

      val testCase: Resource[IO, Unit] = for {
        blocker <- Blocker[IO]
        parseQueue <- testQueue[EnqueueScrapeResult]
        historyName <- givenId
        signalServerPair <- signalServer(url, date.getMillis)
        client <- BlazeClientBuilder[IO](blocker.blockingContext).resource
        stream = consumeParseStream(client, parseQueue.dequeue, FileSystem.persistToDisk(historyName, storeDirectory, blocker), List(signalServerPair._1))
        _ <- Resource.liftF(parseQueue.enqueue1(EnqueueScrapeResult(EnqueueRetryRequest(url, None, 1), html, date)))
        _ <- waitForFirstEntryInStream(stream, 1.second)
        _ <- deleteRecordedFiles(historyName)
        signal <- Resource.liftF(signalServerPair._2)
      } yield signal must beTrue

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "specify callback url to queue entry" in new Context {
      val url = "http://www.google.com"
      val html = "html"
      val date = new DateTime()

      val testCase: Resource[IO, Unit] = for {
        blocker <- Blocker[IO]
        parseQueue <- testQueue[EnqueueScrapeResult]
        historyName <- givenId
        client <- BlazeClientBuilder[IO](blocker.blockingContext).resource
        stream = consumeParseStream(client, parseQueue.dequeue, FileSystem.persistToDisk(historyName, storeDirectory, blocker), List.empty)
        signalServerPair <- signalServer(url, date.getMillis)
        _ <- Resource.liftF(parseQueue.enqueue1(EnqueueScrapeResult(EnqueueRetryRequest(url, Some(signalServerPair._1), 1), html, date)))
        _ <- waitForFirstEntryInStream(stream, 1.second)
        _ <- deleteRecordedFiles(historyName)
        signal <- Resource.liftF(signalServerPair._2)
      } yield signal must beTrue

      testCase.use(_ => IO.unit).unsafeRunSync()
    }
  }

  trait Context extends Scope {
    def testQueue[T]: Resource[IO, InspectableQueue[IO, T]] = Resource.liftF(InspectableQueue.bounded[IO, T](10))

    val givenId: Resource[IO, String] = Resource.liftF(FUUID.randomFUUID[IO].map(_.toString))

    def combineLinkAndParseStreams(blocker: Blocker,
                                   client: Client[IO],
                                   linkQueue: Queue[IO, EnqueueRetryRequest],
                                   parseQueue: Queue[IO, EnqueueScrapeResult],
                                   recordToLog: EnqueueScrapeResult => IO[Unit],
                                   recorderUrls: List[String],
                                   storeDirectory: String,
                                   scrappyDrivers: Stream[IO, ScrappyDriver],
                                   errorReporter: (Throwable, String) => IO[Unit]): Stream[IO, Either[EnqueueScrapeResult, List[Status]]] = {

      Stream(
        consumeLinkStreamAndProduceParseStream(linkQueue.dequeue, linkQueue.enqueue1, parseQueue.enqueue1, scrappyDrivers, errorReporter).map(Left(_)),
        consumeParseStream(client, parseQueue.dequeue, recordToLog, recorderUrls).map(Right(_))
      ).parJoin(2)
    }

    def buildServerResource(historyName: String,
                            urlQueue: EnqueueRetryRequest => IO[Unit],
                            port: Option[Int] = None,
                            serverToken: Option[String] = None): Resource[IO, Server[IO]] =
      Blocker[IO].flatMap((ctx: Blocker) =>
        ScrappyServer.create(
          recordLink = urlQueue,
          serverToken = serverToken,
          port = port,
          recordingStream = FileSystem.streamRecordings(ctx, new File(storeDirectory ++ historyName).toPath),
          retryCount = None
        ).resource
      )

    object RecordedAtParamMatcher extends QueryParamDecoderMatcher[Long]("recorded_at")

    object UrlParamMatcher extends QueryParamDecoderMatcher[String]("url")

    val defaultDrivers: Resource[IO, Stream[IO, ScrappyDriver]] = for {
      queue <- Resource.liftF(Queue.unbounded[IO, ScrappyDriver])
      proxyEnq <- FakeServer.driverLocations
        .map { driverConfigs => driverConfigs._1.map(op => queue.enqueue1(ScrappyDriver(op))) }
      _ <- Resource.liftF(proxyEnq.sequence)
    } yield queue.dequeue

    def signalServer(urlM: String, timeM: Long, port: Option[Int] = None): Resource[IO, (String, IO[Boolean])] = {
      import org.http4s.dsl.io._
      import org.http4s.syntax.kleisli._

      for {
        ref <- Resource.liftF(SignallingRef[IO, Boolean](false))
        srv <- BlazeServerBuilder[IO]
          .bindHttp(port.getOrElse(0))
          .withHttpApp(HttpRoutes.of[IO] {
            case POST -> Root :? RecordedAtParamMatcher(recordedAt) +& UrlParamMatcher(url) =>
              if (urlM == url && timeM == recordedAt) {
                ref.set(true).map(_ => Response(Status.Ok))
              } else IO.pure(Response(Status.NotFound))
          }.orNotFound).resource.map(_.baseUri.renderString)
      } yield (srv, ref.get)
    }

    def readFileContent(urlToScrape: String): Resource[IO, String] = {
      Resource.make(IO.delay {
        val fileName = FileSystem.urlAsFile(urlToScrape).getOrElse(throw new RuntimeException("Can't parse file name"))
        val file = new File(storeDirectory).listFiles().toList.find(_.getName.contains(fileName)).getOrElse(throw new RuntimeException("Can't find file in directory"))
        new BufferedReader(new FileReader(file))
      })(fr => IO(fr.close())).map(_.lines().iterator().asScala.mkString("\n"))
    }

    def matchesIndexContent(scrapQueueHead: String): Resource[IO, MatchResult[String]] = {
      Resource.liftF(IO(scrapQueueHead must
        (contain("Sample") and
          contain("<html lang=\"en\">") and
          contain("</html>") and
          contain("</body>") and
          contain("<body>"))
      ))
    }

    def waitForFirstEntryInStream[T](stream: Stream[IO, T], waitFor: FiniteDuration): Resource[IO, T] = raceWithTimeOut(
      actionToRace = stream.take(1).compile.to[List].map(_.head),
      maxDuration = waitFor
    )

    def deleteRecordedFiles(historyName: String): Resource[IO, Unit] = Blocker[IO]
      .flatMap(bk => Resource.liftF(FileSystem.readFile(bk, new File(storeDirectory ++ historyName).toPath).compile.fold("")(_ ++ _)))
      .flatMap(content =>
        Resource.liftF(content.split('\n')
          .toList
          .map(content => IO.fromEither(decode[HistoryRecording](content)))
          .map(recordIO => recordIO.flatMap(record => IO(new File(storeDirectory + record.fileName).delete())))
          .sequence
        ))
      .flatMap(_ => Resource.liftF(IO(new File(storeDirectory ++ historyName).delete())))

    def raceWithTimeOut[A](actionToRace: IO[A], maxDuration: FiniteDuration): Resource[IO, A] = Resource.liftF[IO, A](
      IO.race(
        timer.sleep(maxDuration).map(_ => new RuntimeException("Failed to retrieve queue record")),
        actionToRace
      ).rethrow
    )

    def fetchContent(url: String): Resource[IO, String] =
      BlazeClientBuilder[IO](executionContext).resource.flatMap(client => Resource.liftF(client.expect[String](url)))

    def enqueueRequest(baseUri: String, request: EnqueueRequest, token: Option[String] = None): Resource[IO, Status] = {
      Resource.liftF(IO.fromEither(Uri.fromString(baseUri ++ "enqueue"))).flatMap(uri =>
        BlazeClientBuilder[IO](executionContext).resource.flatMap(client => Resource.liftF(client.fetch[Status](Request[IO](
          Method.POST,
          uri.withOptionQueryParam("token", token),
          body = EnqueueRequest.enqueueRequestEntityEncoder.toEntity(request).body
        ))(res => IO(res.status)))))
    }
  }
}
