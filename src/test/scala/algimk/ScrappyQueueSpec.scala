package algimk

import java.io.{BufferedReader, File, FileReader}

import algimk.Scrappy.ScrappyDriver
import algimk.ScrappyQueue.{EnqueueRequest, _}
import cats.effect.{ContextShift, IO, Resource, Timer}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef}
import hammock.apache.ApacheInterpreter
import hammock.{HttpResponse, InterpTrans}
import org.http4s.dsl.impl.{QueryParamDecoderMatcher, Root}
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.{HttpRoutes, Response, Status}
import org.joda.time.DateTime
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Spec
import org.specs2.specification.Scope

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class ScrappyQueueSpec(implicit val executionContext: ExecutionContext) extends Spec {

  implicit val contextShift: ContextShift[IO] = IO.contextShift(executionContext)
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val interpreter: InterpTrans[IO] = ApacheInterpreter.instance[IO]

  "ScrappyQueue" should {
    "record a page in scrapping queue" in new Context {
      val url = "http://www.google.com"

      val testCase: Resource[IO, MatchResult[String]] = for {
        drv <- defaultDrivers
        urlQueue <- testQueue[EnqueueRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        server <- testServer(urlQueue, parseQueue, List.empty, storeDirectory, drv)._2.resource
        _ <- request(server.baseUri.renderString, EnqueueRequest(url, None))
        urlQueueHead <- waitForFirstEntryInStream(urlQueue.dequeue, 1000.milliseconds)
      } yield urlQueueHead.url must_=== url

      testCase.use(_ => IO.unit).unsafeRunSync()
    }


    // this will fail if proxies.json is empty
    "use proxies when specified" in new Context {
      val testCase = for {
        drv <- defaultDrivers
        requestRes <- contentOf("https://ipecho.net/plain")
        urlQueue <- testQueue[EnqueueRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        srv = testServer(urlQueue, parseQueue, List.empty, storeDirectory, drv)
        server <- srv._2.resource
        _ <- request(server.baseUri.renderString, EnqueueRequest("https://ipecho.net/plain", None))
        scrapeResult <- waitForFirstEntryInStream(srv._1, 10000.milliseconds).map(_.left.get)
      } yield scrapeResult.html must not(contain(requestRes))

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "reject queue request if it's not authorized and server provides a token" in new Context {
      val url = "http://www.google.com"

      val testCase: Resource[IO, Unit] = for {
        drv <- defaultDrivers
        urlQueue <- testQueue[EnqueueRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        server <- testServer(urlQueue, parseQueue, List.empty, storeDirectory, drv, Some("random-token"))._2.resource
        req <- request(server.baseUri.renderString, EnqueueRequest(url, None)).attempt
      } yield req.map(_.status.code) must beRight(401)

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "allow queue request if it's authorized and server provides a token that matches" in new Context {
      val url = "http://www.google.com"

      val testCase: Resource[IO, Unit] = for {
        drv <- defaultDrivers
        urlQueue <- testQueue[EnqueueRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        server <- testServer(urlQueue, parseQueue, List.empty, storeDirectory, drv, Some("random-token"))._2.resource
        req <- request(server.baseUri.renderString, EnqueueRequest(url, None), Some("random-token")).attempt
      } yield req.map(_.status.code) must beRight(200)

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "record full page scrape result" in new Context {
      val testCase: Resource[IO, Unit] = for {
        drv <- defaultDrivers
        urlQueue <- testQueue[EnqueueRequest]
        parseQueue <- testQueue[EnqueueScrapeResult]
        stream = consumeLinkStreamAndProduceParseStream(urlQueue, parseQueue, drv)
        urlToScrape <- FakeServer.givenFakeServer.map(srv => srv.url.renderString ++ "index.html")
        _ <- Resource.liftF(urlQueue.enqueue1(EnqueueRequest(urlToScrape, None)))
        res <- waitForFirstEntryInStream(stream, 200000.milliseconds)
        _ <- matchesIndexContent(res.html)
        _ <- deleteRecordedFiles()
      } yield ()

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "write file with information retrieved from scrape for historical storage" in new Context {
      val url = "http://www.google.com"
      val fileContentText = "html"

      val testCase: Resource[IO, Unit] = for {
        parseQueue <- testQueue[EnqueueScrapeResult]
        stream = consumeParseStream(parseQueue, List.empty, storeDirectory)
        _ <- Resource.liftF(parseQueue.enqueue1(EnqueueScrapeResult(EnqueueRequest(url, None), fileContentText, new DateTime())))
        _ <- waitForFirstEntryInStream(stream, 1000.milliseconds)
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
        _ <- server(urlQueue, Some(port)).resource
        _ <- request(s"http://localhost:${port}/", EnqueueRequest(url, None))
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
        stream = consumeParseStream(parseQueue, List(signalServerPair._1), storeDirectory)
        _ <- Resource.liftF(parseQueue.enqueue1(EnqueueScrapeResult(EnqueueRequest(url, None), html, date)))
        _ <- waitForFirstEntryInStream(stream, 1000.milliseconds)
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
        stream = consumeParseStream(parseQueue, List.empty, storeDirectory)
        signalServerPair <- signalServer(url, date.getMillis)
        _ <- Resource.liftF(parseQueue.enqueue1(EnqueueScrapeResult(EnqueueRequest(url, Some(signalServerPair._1)), html, date)))
        _ <- waitForFirstEntryInStream(stream, 1000.milliseconds)
        _ <- deleteRecordedFiles()
        signal <- Resource.liftF(signalServerPair._2)
      } yield signal must beTrue

      testCase.use(_ => IO.unit).unsafeRunSync()
    }

    "transform url to valid fileName to save as .html" in {
      ScrappyQueue.urlAsFile("http://www.google.com/something/else") must
        beSome("http_www_google_com_something_else.html")
    }
  }

  trait Context extends Scope {
    def testQueue[T]: Resource[IO, Queue[IO, T]] = Resource.liftF(Queue.bounded[IO, T](10))

    val storeDirectory = "testPageStorage/"

    val defaultDrivers: Resource[IO, List[ScrappyDriver]] = FakeServer
      .driverLocations.map(driverConfigs =>
      driverConfigs._1.flatMap(ScrappyDriver(_, driverConfigs._2).toList)
    )

    object RecordedAtParamMatcher extends QueryParamDecoderMatcher[Long]("recorded_at")

    object UrlParamMatcher extends QueryParamDecoderMatcher[String]("url")

    def signalServer(urlM: String, timeM: Long, port: Option[Int] = None): Resource[IO, (String, IO[Boolean])] = {
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
        val fileName = ScrappyQueue.urlAsFile(urlToScrape).getOrElse(throw new RuntimeException("Can't parse file name"))
        val file = new File(storeDirectory).listFiles().toList.find(_.getName.contains(fileName)).getOrElse(throw new RuntimeException("Can't find file in directory"))
        new BufferedReader(new FileReader(file))
      })(fr => IO(fr.close())).map(_.lines().iterator().asScala.mkString("\n"))
    }

    def matchesIndexContent(scrapQueueHead: String): Resource[IO, MatchResult[String]] = {
      Resource.liftF(IO(scrapQueueHead must (contain("Sample") and contain("<html>") and contain("</html>") and contain("</body>") and contain("<body>"))))
    }

    def waitForFirstEntryInStream[T](stream: Stream[IO, T], waitFor: FiniteDuration): Resource[IO, T] = raceWithTimeOut(
      actionToRace = stream.take(1).compile.to[List].map(_.head),
      maxDuration = waitFor
    )

    def deleteRecordedFiles(): Resource[IO, Unit] =
      Resource.liftF(IO.delay(new File(storeDirectory).listFiles().toList.foreach(_.delete())))

    def testServer(linkQueue: Queue[IO, EnqueueRequest],
                   parseQueue: Queue[IO, EnqueueScrapeResult],
                   recorderUrls: List[String],
                   storeDirectory: String,
                   scrappyDriver: List[ScrappyDriver],
                   token: Option[String] = None): (Stream[IO, Either[EnqueueScrapeResult, List[HttpResponse]]], BlazeServerBuilder[IO]) =
      (combineLinkAndParseStreams(linkQueue, parseQueue, List.empty, storeDirectory, scrappyDriver), server(linkQueue, serverToken = token))

    def raceWithTimeOut[A](actionToRace: IO[A], maxDuration: FiniteDuration): Resource[IO, A] = Resource.liftF[IO, A](
      IO.race(
        timer.sleep(maxDuration).map(_ => new RuntimeException("Failed to retrieve queue record")),
        actionToRace
      ).rethrow
    )

    def contentOf(url: String): Resource[IO, String] = {
      import hammock._

      Resource.liftF(
        Hammock.request(Method.GET, uri"${url}", Map.empty).map(_.entity.cata[String](
          _.content,
          cnt => new String(cnt.content),
          _ => ""
        )).exec[IO]
      )

    }

    def request(baseUri: String, request: EnqueueRequest, token: Option[String] = None): Resource[IO, HttpResponse] = {
      import hammock._
      import hammock.circe.implicits._

      Resource.liftF(
        Hammock.request[EnqueueRequest](Method.POST, uri"${baseUri}enqueue".params(token.map("token" -> _).toList: _*), Map(), Some(request)).exec[IO]
      )
    }
  }

}
