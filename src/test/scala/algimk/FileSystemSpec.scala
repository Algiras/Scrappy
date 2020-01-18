package algimk

import java.io.File

import cats.effect.{Blocker, ContextShift, IO}
import org.specs2.mutable.Spec

import scala.concurrent.ExecutionContext

class FileSystemSpec(implicit executionContext: ExecutionContext) extends Spec {
  sequential

  implicit val contextShift: ContextShift[IO] = IO.contextShift(executionContext)

  "FileSystem" should {
    "createDirectoryIfNotExist" should {
      "create directory" in {
        Blocker[IO].use(bk => for {
          dir <- FileSystem.createDirectoryIfNotExist(bk, new File("temp").toPath).attempt
          _ <- fs2.io.file.delete[IO](bk, new File("temp").toPath)
        } yield dir must beRight).unsafeRunSync()
      }

      "if directory exists don't blow up" in {
        Blocker[IO].use(bk => for {
          _ <- FileSystem.createDirectoryIfNotExist(bk, new File("temp").toPath)
          dir <- FileSystem.createDirectoryIfNotExist(bk, new File("temp").toPath).attempt
          _ <- fs2.io.file.delete[IO](bk, new File("temp").toPath)
        } yield dir must beRight).unsafeRunSync()
      }
    }
  }

}
