package algimk

import algimk.Scrappy.ScrappyFn
import cats.data.Kleisli
import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import org.http4s.Uri
import org.openqa.selenium.WebDriver
import org.specs2.mutable.Spec
import org.specs2.specification.Scope

import scala.concurrent.ExecutionContext

class ScrappySpec(implicit val executionContext: ExecutionContext) extends Spec with FakeServerContext {
  implicit def contextShift: ContextShift[IO] = IO.contextShift(executionContext)
  implicit def timer: Timer[IO] = IO.timer(executionContext)

  "Scrappy" should {
    "read page body" in new Context {
      runTest((baseUri: Uri) => for {
          _ <- indexPage(baseUri)
          elem <- Scrappy.gerElementByCssSelector("body")
          _ <- assert(elem.map(_.getText) must beSome("Sample"))
        } yield ()
      )
    }

    "read all html" in new Context {
      def trimContent(content: String): String = content.trim.filterNot(_ == '\n')

      runTest((baseUri: Uri) => for {
        _ <- indexPage(baseUri)
          elem <- Scrappy.gerElementByCssSelector("html")
        indexContext <- Kleisli.liftF(FakeServer.loadFileResource("index.html"))
      } yield elem.map(el => trimContent(el.html)) must beSome(trimContent(indexContext))
      )
    }

    "read all inner html" in new Context {
      def trimContent(content: String): String = content.trim.filterNot(_ == '\n')

      runTest((baseUri: Uri) => for {
        _ <- indexPage(baseUri)
        elem <- Scrappy.gerElementByCssSelector("body")
      } yield elem.map(el => trimContent(el.innerHtml)) must beSome("Sample")
      )
    }

    "read children of an element by selector" in new Context {
      runTest((baseUri: Uri) => for {
        _ <- pagedPage(baseUri)
        elem <- Scrappy.gerElementByCssSelector(".pagination")
        elements = elem.toList.flatMap(_.getChildren(".page-bt").map(_.getText))
      } yield elements must_=== List(2, 3, 4, 5, 110).map(_.toString) ++ List("»")
      )
    }

    "read attribute of an element by selector" in new Context {
      runTest((baseUri: Uri) => for {
        _ <- pagedPage(baseUri)
        elem <- Scrappy.gerElementByCssSelector(".pagination")
        elements = elem.toList.flatMap(_.getChildren(".page-bt").map(_.getAttribute("href")))
      } yield elements must_=== List(2, 3, 4, 5, 110, 2).map(id => Some(s"$baseUri$id/"))
      )
    }

    "visit child pages" in new Context {
      def getLinks = for {
        elem <- Scrappy.gerElementByCssSelector(".pagination")
      } yield elem.toList.flatMap(_.getChildren(".page-bt").map(_.getAttribute("href"))).collect{
        case Some(link) => link
      }

      runTest((baseUri: Uri) => for {
        _ <- queuePage(baseUri)
        elements <- getLinks
        links <- elements.map(url => for  {
          _ <- Scrappy.get(url)
          res <- Scrappy.gerElementByCssSelector("#test")
            .flatMapF(el => if(el.isDefined)
              IO(el.get.getText)
            else
              IO.raiseError(new RuntimeException("Failure retrieving element"))
            )
        } yield res).foldLeftM(List.empty[String])((acc, v) => v.flatMap(nxt => Kleisli[IO, WebDriver, List[String]](_ => IO(nxt :: acc))))
      } yield links.toSet[String] must_=== List(2, 3, 4).map(_.toString).toSet[String])
    }
  }

  trait Context extends Scope {
    def indexPage(baseUri: Uri): ScrappyFn[WebDriver, Unit] = Scrappy.get(baseUri.renderString + "index.html")
    def pagedPage(baseUri: Uri): ScrappyFn[WebDriver, Unit] = Scrappy.get(baseUri.renderString + "paged.html")
    def queuePage(baseUri: Uri): ScrappyFn[WebDriver, Unit] = Scrappy.get(baseUri.renderString + "queue/1.html")
  }

}
