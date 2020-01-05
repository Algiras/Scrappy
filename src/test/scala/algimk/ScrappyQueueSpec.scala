package algimk

import java.io.{BufferedReader, File, FileReader}

import algimk.Scrappy.ScrappyDriver
import algimk.ScrappyQueue.{EnqueueRequest, _}
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

class ScrappyQueueSpec(implicit val executionContext: ExecutionContext) extends Spec{
  implicit val contextShift: ContextShift[IO] = IO.contextShift(executionContext)
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val jawnFacade = new io.circe.jawn.CirceSupportParser(None, allowDuplicateKeys = false).facade

  "ScrappyQueue" should {
    "record a page in scrapping queue" in new Context {
      val url = "http://www.google.com"

      val testCase: Resource[IO, MatchResult[String]] = for {
        urlQueue <- testQueue[EnqueueRequest]
        server <- buildServerResource(urlQueue)
        _ <- enqueueRequest(server.baseUri.renderString, EnqueueRequest(url, None))
        urlQueueHead <- waitForFirstEntryInStream(urlQueue.dequeue, 1.second)
      } yield urlQueueHead.url must_=== url

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "use proxies when specified" in new Context {
      val testCase: Resource[IO, MatchResult[String]] = for {
        driverQueue <- Resource.liftF(Queue.unbounded[IO, ScrappyDriver])
        prx <- FakeServer.proxy
        drivers <- Resource.liftF(FakeServer.driverLocations.map{
          case (drv, _) => drv.map(d => driverQueue.enqueue1(ScrappyDriver(d, prx)))
        })
        _ <- Resource.liftF(drivers.sequence)
        pongUrl <- FakeServer.pongServer(_.headers.get(CaseInsensitiveString("Host")).get.value)
        requestRes <- fetchContent(pongUrl)
        urlQueue <- testQueue[EnqueueRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        stream = combineLinkAndParseStreams(urlQueue, parseQueue, List.empty, storeDirectory, driverQueue.dequeue, (_, _) => IO.unit)
        server <- buildServerResource(urlQueue)
        _ <- enqueueRequest(server.baseUri.renderString, EnqueueRequest(pongUrl, None))
        scrapeResult <- waitForFirstEntryInStream(stream, 10000.seconds).map(_.left.get)
      } yield scrapeResult.html must not(contain(requestRes))

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "reject queue request if it's not authorized and server provides a token" in new Context {
      val testCase: Resource[IO, Unit] = for {
        urlQueue <- testQueue[EnqueueRequest]
        server <- buildServerResource(urlQueue, serverToken = Some("random-token"))
        req <- enqueueRequest(server.baseUri.renderString, EnqueueRequest("http://www.google.com", None)).attempt
      } yield req.map(_.code) must beRight(401)

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "allow queue request if it's authorized and server provides a token that matches" in new Context {
      val testCase: Resource[IO, Unit] = for {
        urlQueue <- testQueue[EnqueueRequest]
        server <- buildServerResource(urlQueue, serverToken = Some("random-token"))
        req <- enqueueRequest(server.baseUri.renderString, EnqueueRequest("http://www.google.com", None), Some("random-token")).attempt
      } yield req.map(_.code) must beRight(200)

      testCase.use(_ => IO.unit).unsafeRunSync()
    }


    "record full page scrape result" in new Context {
      val testCase: Resource[IO, Unit] = for {
        drv <- defaultDrivers
        urlQueue <- testQueue[EnqueueRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        stream = consumeLinkStreamAndProduceParseStream(urlQueue.dequeue, urlQueue.enqueue1, parseQueue, drv.dequeue, (_, _) => IO.unit)
        urlToScrape <- FakeServer.givenFakeServer.map(srv => srv.url.renderString ++ "index.html")
        _ <- Resource.liftF(urlQueue.enqueue1(EnqueueRequest(urlToScrape, None)))
        res <- waitForFirstEntryInStream(stream, 20.seconds)
        _ <- matchesIndexContent(res.html)
        _ <- deleteRecordedFiles()
      } yield ()

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "provide historical replay of previously recorded pages" in new Context {
      def requestResult(uri: Uri): Resource[IO, Stream[IO, Recording]] = BlazeClientBuilder[IO](executionContext).resource.map(
        _.stream(Request[IO](Method.GET, uri)).flatMap(res => res.body.chunks.parseJsonStream)
          .flatMap(j => Stream.eval(IO(recordingDecoder.decodeJson(j)).rethrow))
      )

      val testCase: Resource[IO, Unit] = for {
        drv <- defaultDrivers
        urlQueue <- testQueue[EnqueueRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        basePath <- buildServerResource(urlQueue).map(_.baseUri)
        stream = combineLinkAndParseStreams(urlQueue, parseQueue, List.empty, storeDirectory, drv.dequeue, (_, _) => IO.raiseError(new RuntimeException("Failure to parse")))
        urlToScrape1 <- FakeServer.givenFakeServer.map(srv => srv.url.renderString ++ "index.html")
        urlToScrape2 <- FakeServer.givenFakeServer.map(srv => srv.url.renderString ++ "index.html")
        _ <- Resource.liftF(urlQueue.enqueue1(EnqueueRequest(urlToScrape1, None)))
        _ <- Resource.liftF(urlQueue.enqueue1(EnqueueRequest(urlToScrape2, None)))
        recordedRes <- Resource.liftF(stream.take(4).compile.to[List].map(_.collect{ case Left(value) => value }.map(r => Recording(r.time, r.html.split('\n').mkString("")))).timeout(1000.seconds))
        stm <- requestResult(basePath / "replay")
        suppliedRes <- Resource.liftF(stm.take(2).compile.toList)
        _ <- deleteRecordedFiles()
      } yield recordedRes must containAnyOf(suppliedRes)

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "retrieve list of .html files content in the directory" in new Context {
      val recording1 = Recording(new DateTime(), "html1")
      val recording2 = Recording(new DateTime(), "html2")

      def writeRecord(recording: Recording): IO[Unit] = Blocker[IO].use(f = ctx =>
        FileSystem.writeFile(
          ctx,
          storeDirectory + recording.at.getMillis.toString + "-test.html",
          recording.html
        )
      )

      val testCase: IO[List[Recording]] = for {
        _ <- writeRecord(recording1)
        _ <- writeRecord(recording2)
        files <- Blocker[IO].use(streamRecordings(_, new File(storeDirectory).toPath).take(2).compile.toList)
        _ <- deleteRecordedFiles().use(IO.pure)
      } yield files

      testCase.unsafeRunSync() must contain(exactly(recording1, recording2))
    }

    "consider empty page to be a scrape failure" in new Context {
      val testCase: Resource[IO, Unit] = for {
        drv <- defaultDrivers
        urlQueue <- testQueue[EnqueueRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        stream = consumeLinkStreamAndProduceParseStream(urlQueue.dequeue, urlQueue.enqueue1, parseQueue, drv.dequeue, (_, _) => IO.unit)
        urlToScrape <- FakeServer.givenFakeServer.map(srv => srv.url.renderString ++ "empty.html")
        _ <- Resource.liftF(urlQueue.enqueue1(EnqueueRequest(urlToScrape, None)))
        res <- waitForFirstEntryInStream(stream, 10.seconds).attempt
        _ <- Resource.liftF(IO(res must beLeft))
      } yield ()

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "on scrape failure report error and continue working" in new Context {
      val testCase: Resource[IO, Unit] = for {
        drv <- defaultDrivers
        urlQueue <- testQueue[EnqueueRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        ref <- Resource.liftF(Ref.of[IO, Either[String, Throwable]](Left("Was not called")))
        stream = consumeLinkStreamAndProduceParseStream(urlQueue.dequeue, urlQueue.enqueue1, parseQueue, drv.dequeue, (error, _) => ref.set(Right(error)))
        _ <- Resource.liftF(urlQueue.enqueue1(EnqueueRequest("https://127.0.0.1", None)))
        res <- waitForFirstEntryInStream(stream, 20.seconds).attempt
        err <- Resource.liftF(ref.get)
        _ <- Resource.liftF(IO(err must beRight))
        _ <- Resource.liftF(IO(res must beLeft))
      } yield ()

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "on scrape failure, recover by retry" in new Context {
      val testCase: Resource[IO, Unit] = for {
        drv <- defaultDrivers
        urlQueue <- testQueue[EnqueueRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        ref <- Resource.liftF(Ref.of[IO, Either[String, Throwable]](Left("Was not called")))
        stream = consumeLinkStreamAndProduceParseStream(urlQueue.dequeue, urlQueue.enqueue1, parseQueue, drv.dequeue, (error, _) => ref.set(Right(error)))
        _ <- Resource.liftF(urlQueue.enqueue1(EnqueueRequest("https://127.0.0.1", None)))
        res <- waitForFirstEntryInStream(stream, 20.seconds).attempt
        err <- Resource.liftF(ref.get)
        _ <- Resource.liftF(IO(err must beRight))
        _ <- Resource.liftF(IO(res must beLeft))
      } yield ()

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "write file with information retrieved from scrape for historical storage" in new Context {
      val url = "http://www.google.com"
      val fileContentText = "html"

      val testCase: Resource[IO, Unit] = for {
        parseQueue <- testQueue[EnqueueScrapeResult]
        stream = consumeParseStream(parseQueue.dequeue, List.empty, storeDirectory)
        _ <- Resource.liftF(parseQueue.enqueue1(EnqueueScrapeResult(EnqueueRequest(url, None), fileContentText, new DateTime())))
        _ <- waitForFirstEntryInStream(stream, 1.second)
        fileContent <- readFileContent(url)
        _ <- Resource.liftF(IO(fileContentText must_=== fileContent))
        _ <- deleteRecordedFiles()
      } yield ()

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "specify application port" in new Context {
      val url = "http://www.google.com"
      val port = 8090

      val testCase: Resource[IO, Unit] = for {
        urlQueue <- testQueue[EnqueueRequest]
        _ <- buildServerResource(urlQueue, Some(port))
        _ <- enqueueRequest(s"http://localhost:${port}/", EnqueueRequest(url, None))
      } yield ()

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "specify external recorder urls" in new Context {
      val url = "http://www.google.com"
      val html = "html"
      val date = new DateTime()

      val testCase: Resource[IO, Unit] = for {
        parseQueue <- testQueue[EnqueueScrapeResult]
        signalServerPair <- signalServer(url, date.getMillis)
        stream = consumeParseStream(parseQueue.dequeue, List(signalServerPair._1), storeDirectory)
        _ <- Resource.liftF(parseQueue.enqueue1(EnqueueScrapeResult(EnqueueRequest(url, None), html, date)))
        _ <- waitForFirstEntryInStream(stream, 1.second)
        _ <- deleteRecordedFiles()
        signal <- Resource.liftF(signalServerPair._2)
      } yield signal must beTrue

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "specify callback url to queue entry" in new Context {
      val url = "http://www.google.com"
      val html = "html"
      val date = new DateTime()

      val testCase: Resource[IO, Unit] = for {
        parseQueue <- testQueue[EnqueueScrapeResult]
        stream = consumeParseStream(parseQueue.dequeue, List.empty, storeDirectory)
        signalServerPair <- signalServer(url, date.getMillis)
        _ <- Resource.liftF(parseQueue.enqueue1(EnqueueScrapeResult(EnqueueRequest(url, Some(signalServerPair._1)), html, date)))
        _ <- waitForFirstEntryInStream(stream, 1.second)
        _ <- deleteRecordedFiles()
        signal <- Resource.liftF(signalServerPair._2)
      } yield signal must beTrue

      testCase.use(_ => IO.unit).unsafeRunSync()
    }
  }

  trait Context extends Scope {
    def testQueue[T]: Resource[IO, InspectableQueue[IO, T]] = Resource.liftF(InspectableQueue.bounded[IO, T](10))

    val storeDirectory = "testPageStorage/"

    def buildServerResource(urlQueue: Queue[IO, EnqueueRequest], port: Option[Int] = None, serverToken: Option[String] = None): Resource[IO, Server[IO]] =
      Blocker[IO].flatMap(ctx => server(urlQueue, ctx, new File(storeDirectory).toPath, serverToken = serverToken, port = port).resource)

    object RecordedAtParamMatcher extends QueryParamDecoderMatcher[Long]("recorded_at")
    object UrlParamMatcher extends QueryParamDecoderMatcher[String]("url")

    val defaultDrivers: Resource[IO, Queue[IO, ScrappyDriver]] = Resource.liftF(for {
      queue <- Queue.unbounded[IO, ScrappyDriver]
      proxyEnq <- FakeServer.driverLocations
        .map { driverConfigs => driverConfigs._1.map(op => queue.enqueue1(ScrappyDriver(op))) }
      _ <- proxyEnq.sequence
    } yield queue)

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

    def deleteRecordedFiles(): Resource[IO, Unit] =
      Resource.liftF(IO.delay(new File(storeDirectory).listFiles().toList.foreach(_.delete())))

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
        body = enqueueRequestEntityEncoder.toEntity(request).body
      ))(res => IO(res.status)))))
    }
  }

}
