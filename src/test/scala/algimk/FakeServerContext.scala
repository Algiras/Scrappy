package algimk

import algimk.FakeServer._
import algimk.Scrappy.{ScrappyDriver, ScrappyFn}
import algimk.config.{Config, DriverConfig, HttpProxy, ProxyConfig}
import cats.data.Kleisli
import cats.effect.{Blocker, ContextShift, IO, Resource, Timer}
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.{EntityEncoder, Header, HttpRoutes, Request, Response, Status, Uri}
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.staticcontent.{ResourceService, _}
import org.openqa.selenium.WebDriver
import org.specs2.matcher.MatchResult
import pureconfig.generic.auto._
import pureconfig.module.catseffect.loadConfigF
import java.io.File

import scala.concurrent.ExecutionContext
import scala.io.Source

trait FakeServerContext {
  implicit def executionContext: ExecutionContext
  implicit def contextShift: ContextShift[IO]
  implicit def timer: Timer[IO]

  case class Env(driver: WebDriver, baseUri: Uri)

  val context: Resource[IO, Env] = for {
    driver <- defaultDriver
    server <- givenFakeServer
  } yield Env(driver, server.url)

  def getPage(baseUri: Uri)(path: String): ScrappyFn[WebDriver, Unit] =
    Scrappy.get(baseUri.renderString + path)

  def assert[T](mr: MatchResult[T]): Kleisli[IO, WebDriver, T] =
    Kleisli.liftF(IO(mr.orThrow).map(_.expectable.value))

  def runTest[T](fn: Uri => Kleisli[IO, WebDriver, T]): T =
    context.use(env => fn(env.baseUri).run(env.driver)).unsafeRunSync()
}

object FakeServer {
  case class ServerApi(url: Uri)

  def driverLocations(implicit ctxS: ContextShift[IO]): Resource[IO, (List[DriverConfig], List[ProxyConfig])] = for {
    blocker <- Blocker[IO]
    config <- Resource.liftF(loadConfigF[IO, Config])
    proxies <- Resource.liftF(ProxyConfig.readProxies(blocker, config.proxyConfigFileName.map(new File(_).toPath())))
  } yield (config.browserDrivers, proxies)

  def defaultDriver(implicit ctxS: ContextShift[IO]): Resource[IO, WebDriver] = for {
    blocker <- Blocker[IO]
    drv <- driverLocations
    d <- Scrappy.driver(drv._1.map(opt => ScrappyDriver(opt)).head)
  } yield d

  def loadFileResource(fileName: String) = IO(Source.fromResource(s"testPages/$fileName").getLines.mkString)

  def givenFakeServer(implicit ctxS: ContextShift[IO], tm: Timer[IO]): Resource[IO, ServerApi] = {
    import org.http4s.syntax.kleisli._

    for {
      blocker <- Blocker[IO]
      server <- BlazeServerBuilder[IO]
        .bindHttp(0)
        .withHttpApp(resourceService[IO](ResourceService.Config("/testPages", blocker)).orNotFound)
        .resource.map(server => ServerApi(server.baseUri))
    } yield server

  }


  def pongServer[T](givenRequest: Request[IO] => T)(implicit w: EntityEncoder[IO, T], ctxS: ContextShift[IO], tm: Timer[IO]): Resource[IO, String] = {
    import org.http4s.syntax.kleisli._

    BlazeServerBuilder[IO]
      .bindHttp(0)
      .withHttpApp(HttpRoutes.of[IO] {
        case req => IO(Response(Status.Ok).withEntity(givenRequest(req)))
      }.orNotFound).resource.map(_.baseUri.renderString)
  }

  def proxy(implicit ctxS: ContextShift[IO], tm: Timer[IO], ex: ExecutionContext): Resource[IO, ProxyConfig] = {
    import org.http4s.syntax.kleisli._

    for {
      client <- BlazeClientBuilder[IO](ex).resource
      server <- BlazeServerBuilder[IO].bindHttp(0).withHttpApp(HttpRoutes.of[IO] {
        case req =>
          client.fetch(req.withUri(req.uri).withHeaders(req.headers.put(Header("Host", s"${req.remoteHost.get}:${req.serverPort}"))))(IO.pure)
      }.orNotFound).resource.map(srv => HttpProxy(s"${srv.baseUri.host.get.value}:${srv.baseUri.port.get}"))
    } yield server
  }
}
