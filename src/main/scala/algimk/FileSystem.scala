package algimk

import java.io.{BufferedWriter, FileWriter}
import cats.effect.IO
import scala.io.Source

private[algimk] case object FileSystem {
  def readFile(fileName: String): IO[String] = {
    IO(Source.fromFile(fileName)).bracket(
      bf => IO(bf.getLines().mkString(""))
    )(bf => IO(bf.close()))
  }

  def writeFile(fileName: String, html: String): IO[Unit] = IO.delay {
    new BufferedWriter(new FileWriter(fileName))
  }.bracket(bf => IO(bf.write(html)))(bf => IO(bf.close()))
}
