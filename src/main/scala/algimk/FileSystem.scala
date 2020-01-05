package algimk

import java.io.File
import cats.effect.{Blocker, ContextShift, IO}
import fs2.Stream
import fs2.io.file._
import fs2.text

case object FileSystem {
  def readFile(blocker: Blocker, fileName: String)(implicit ctxS: ContextShift[IO]): IO[String] = {
    readAll[IO](new File(fileName).toPath, blocker, 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .compile.fold("")(_ ++ _)
  }

  def writeFile(blocker: Blocker, fileName: String, content: String)(implicit ctxS: ContextShift[IO]): IO[Unit] = {
    Stream.emit[IO, String](content)
      .through(text.utf8Encode)
      .through(writeAll[IO](new File(fileName).toPath, blocker)
    ).compile.drain
  }

  def createDirectoryIfNotExist(blocker: Blocker, storeDirectory: String)(implicit ctxS: ContextShift[IO]): IO[Unit] = {
    val path = new File(storeDirectory).toPath

    exists[IO](blocker, path).flatMap(exists => {
      if(!exists) {
        createDirectory[IO](blocker, path).map(_ => ())
      } else {
        IO.unit
      }
    })
  }

  def urlAsFile(url: String): Option[String] = {
    val parsedUrl = url
      .replace('.', '_')
      .replace('/', '_')
      .replace(':', '_')
      .replaceAll("_+", "_")
      .trim

    if (parsedUrl.nonEmpty) {
      Some(parsedUrl ++ ".html")
    } else {
      None
    }
  }
}
