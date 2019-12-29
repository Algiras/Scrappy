package algimk

import cats.data.Kleisli
import cats.effect.{IO, Resource}
import org.openqa.selenium.{By, WebDriver, WebElement}
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxOptions}

import scala.collection.JavaConverters._

object Scrappy {
  sealed trait ScrappyDriver {
    def driver: WebDriver
  }

  case class Firefox(driverSource: String) extends ScrappyDriver {
    override def driver: WebDriver = {
      System.setProperty("webdriver.gecko.driver", driverSource)
      val options: FirefoxOptions = new FirefoxOptions()
      options.setHeadless(true)

      new FirefoxDriver(options)
    }
  }

  type ScrappyFn[A, B] = Kleisli[IO, A, B]

  trait WElement {
    def html: String
    def getText: String
    def getAttribute(name: String): Option[String]
    def getChildren(selector: String): List[WElement]
  }

  object WElement {
    def apply(element: WebElement): WElement = new WElement {
      override def html: String = element.getAttribute("outerHTML")
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

  def gerElementsByCssSelector(selector: String): ScrappyFn[WebDriver, List[WElement]] =
    Kleisli(driver => IO(driver.findElements(By.cssSelector(selector)).asScala.map(WElement.apply).toList))

  def gerElementByCssSelector(selector: String): ScrappyFn[WebDriver, Option[WElement]] =
    gerElementsByCssSelector(selector).map(_.headOption)
}