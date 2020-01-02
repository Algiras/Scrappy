package algimk

import algimk.config._
import cats.data.{Kleisli, NonEmptyList}
import cats.effect.{IO, Resource}
import org.openqa.selenium.Proxy.ProxyType
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxOptions}
import org.openqa.selenium.{By, Proxy, WebDriver, WebElement}

import scala.collection.JavaConverters._

object Scrappy {
  sealed trait ScrappyProxy {
    def proxy: Proxy
  }

  object ScrappyProxy {
    def apply(conf: ProxyConfig): ScrappyProxy = new ScrappyProxy {
      override def proxy: Proxy = {
        val proxy = new Proxy()

        proxy.setAutodetect(false)
        proxy.setProxyType(ProxyType.MANUAL)

        conf match {
          case FTPProxy(url) => proxy.setFtpProxy(url)
          case SSLProxy(url) => proxy.setSslProxy(url)
          case HttpProxy(url) => proxy.setHttpProxy(url)
          case Socks4Proxy(url) =>
            proxy.setSocksProxy(url)
            proxy.setSocksVersion(4)
          case Socks5Proxy(url) =>
            proxy.setSocksProxy(url)
            proxy.setSocksVersion(5)
          case AutoConfigProxy(url) => proxy.setProxyAutoconfigUrl(url)
        }

        proxy
      }
    }
  }

  sealed trait ScrappyDriver {
    def driver: WebDriver
  }

  object ScrappyDriver {
    def apply(config: DriverConfig, proxies: List[ProxyConfig]): NonEmptyList[ScrappyDriver] = {
      def buildWithProxies(build: Option[ScrappyProxy] => ScrappyDriver): NonEmptyList[ScrappyDriver] = {
        NonEmptyList.fromList(proxies).map(prxs => {
          prxs.map(ScrappyProxy.apply).map(Some(_)).map(build)
        }).getOrElse(NonEmptyList.one(build(None)))
      }

      config match {
        case FirefoxConfig(path) => buildWithProxies(Firefox(path, _))
        case ChromeConfig(path) => buildWithProxies(Chrome(path, _))
      }
    }
  }

  private case class Firefox(driverSource: String, proxy: Option[ScrappyProxy]) extends ScrappyDriver {
    override def driver: WebDriver = {
      System.setProperty("webdriver.gecko.driver", driverSource)
      val options: FirefoxOptions = new FirefoxOptions()
      options.setHeadless(true)
      proxy.foreach(scrappyProxy => {
        options.setProxy(scrappyProxy.proxy)
      })

      new FirefoxDriver(options)
    }
  }

  private case class Chrome(driverSource: String, proxy: Option[ScrappyProxy]) extends ScrappyDriver {
    override def driver: WebDriver = {
      System.setProperty("webdriver.chrome.driver", driverSource)
      val options: ChromeOptions = new ChromeOptions()
      options.setHeadless(true)
      proxy.foreach(scrappyProxy => {
        options.setProxy(scrappyProxy.proxy)
      })

      new ChromeDriver(options)
    }
  }

  type ScrappyFn[A, B] = Kleisli[IO, A, B]

  trait WElement {
    def html: String
    def innerHtml: String
    def getText: String
    def getAttribute(name: String): Option[String]
    def getChildren(selector: String): List[WElement]
  }

  object WElement {
    def apply(element: WebElement): WElement = new WElement {
      override def html: String = element.getAttribute("outerHTML")
      override def innerHtml: String = element.getAttribute("innerHTML")
      override def getText: String = element.getText
      override def getAttribute(name: String): Option[String] = Option(element.getAttribute(name))
      override def getChildren(selector: String): List[WElement] = element.findElements(By.cssSelector(selector))
        .asScala.toList.map(apply)
    }
  }

  def driver(webDriver: ScrappyDriver): Resource[IO, WebDriver] = {
    Resource.make(IO(webDriver.driver))(driver => IO(driver.quit()))
  }

  def get(url: String): ScrappyFn[WebDriver, Unit] = Kleisli(driver => IO(driver.get(url)))

  def getElementsByCssSelector(selector: String): ScrappyFn[WebDriver, List[WElement]] =
    Kleisli(driver => IO(driver.findElements(By.cssSelector(selector)).asScala.map(WElement.apply).toList))

  def gerElementByCssSelector(selector: String): ScrappyFn[WebDriver, Option[WElement]] =
    getElementsByCssSelector(selector).map(_.headOption)
}