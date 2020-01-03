package algimk

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.Files

import cats.effect.IO

import scala.io.Source

case object FileSystem {
  def readFile(fileName: String): IO[String] = {

    IO(Source.fromFile(fileName)).bracket(
      bf => IO(bf.getLines().mkString(""))
    )(bf => IO(bf.close()))
  }

  def writeFile(fileName: String, content: String): IO[Unit] = IO.delay {
    new BufferedWriter(new FileWriter(fileName))
  }.bracket(bf => IO(bf.write(content)))(bf => IO(bf.close()))

  def createDirectoryIfNotExist(storeDirectory: String): IO[Any] = {
    IO.delay {
      val folderPath = new File(storeDirectory).toPath
      if (!Files.exists(folderPath)) {
        Files.createDirectory(folderPath)
      }
    }
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
