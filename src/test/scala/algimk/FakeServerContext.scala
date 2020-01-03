package algimk

import algimk.FakeServer._
import algimk.Scrappy.{ScrappyDriver, ScrappyFn}
import algimk.config.{Config, DriverConfig, ProxyConfig}
import cats.data.Kleisli
import cats.effect.{ContextShift, IO, Resource, Timer}
import org.http4s.Uri
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.staticcontent.{ResourceService, _}
import org.http4s.syntax.kleisli._
import org.openqa.selenium.WebDriver
import org.specs2.matcher.MatchResult
import pureconfig.generic.auto._
import pureconfig.module.catseffect.loadConfigF

import scala.concurrent.ExecutionContext
import scala.io.Source

trait FakeServerContext {
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

  def driverLocations: IO[(List[DriverConfig], List[ProxyConfig])] = for {
    config <- loadConfigF[IO, Config]
    proxies <- ScrappyQueue.readProxies(config.proxyConfigFileName)
  } yield (config.browserDrivers, proxies)

  val defaultDriver: Resource[IO, WebDriver] = {
    Resource.liftF(driverLocations).flatMap(drv => Scrappy.driver(drv._1.map(opt => ScrappyDriver(opt)).head))
  }

  def loadFileResource(fileName: String) = IO(Source.fromResource(s"testPages/$fileName").getLines.mkString)

  def givenFakeServer(implicit ctxS: ContextShift[IO], tm: Timer[IO]): Resource[IO, ServerApi] = {
    BlazeServerBuilder[IO]
      .bindHttp(0)
      .withHttpApp(resourceService[IO](ResourceService.Config("/testPages", ExecutionContext.global)).orNotFound)
      .resource.map(server => ServerApi(server.baseUri))
  }
}
