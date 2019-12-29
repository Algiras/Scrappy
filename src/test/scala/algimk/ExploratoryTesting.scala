package algimk

import cats.data.Kleisli
import cats.effect.concurrent.Ref
import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef}
import org.http4s.Uri
import org.specs2.mutable.Spec
import org.specs2.specification.Scope

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class ExploratoryTesting(implicit val executionContext: ExecutionContext) extends Spec with FakeServerContext {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(executionContext)
  implicit val timer: Timer[IO] = IO.timer(executionContext)

  "queuing" in new Context {
    context.use(env => {
      (for {
        ref <- Stream.eval(Ref.of[IO, Set[String]](Set.empty[String]))
        q1 <- Stream.eval(Queue.unbounded[IO, String])
        q2 <- Stream.eval(Queue.bounded[IO, String](1))
        signal <- Stream.eval(SignallingRef[IO, Boolean](false))
        _ <- Stream.eval(q1.enqueue1(env.baseUri.renderString ++ "queue/1.html"))
        _ <- Stream(
          q1.dequeue.through(q2.enqueue),
          Stream.fixedRate(5.seconds).evalMap(_ => ref.get.flatMap(set => if(set.size == 4) signal.set(true) else IO.unit)),
          q2.dequeue.evalMap(link => for {
            contains <- ref.get.map(_.contains(link))
            _ <- timer.sleep(1.seconds)
            _ <- if (!contains) (for {
              _ <- Kleisli.liftF(IO(println(link)))
              _ <- Scrappy.get(link)
              _ <- Kleisli.liftF(ref.modify(st => (st + link, ())))
              links <- getLinks
              _ <- Kleisli.liftF(links.map(link => q1.enqueue1(link)).sequence)
            } yield ()).run(env.driver) else IO.unit
          } yield ()
          )
        ).parJoin(3).interruptWhen(signal)
      } yield ()).compile.drain
    }).unsafeRunSync()
  }

trait Context extends Scope {
  def queuePage(baseUri: Uri) = Scrappy.get(baseUri.renderString + "queue/1.html")

  def getLinks = for {
    elem <- Scrappy.gerElementByCssSelector(".pagination")
  } yield elem.toList.flatMap(_.getChildren(".page-bt").map(_.getAttribute("href"))).collect {
    case Some(link) => link
  }
}

}
