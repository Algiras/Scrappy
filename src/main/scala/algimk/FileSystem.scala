package algimk

import java.io.{File, FileWriter}
import java.nio.file.Path

import algimk.model.{EnqueueScrapeResult, HistoryRecording, Recording}
import cats.effect.{Blocker, ContextShift, IO, Resource}
import io.circe.parser.decode
import fs2.Stream
import fs2.io.file._
import fs2.text
import model._
import io.circe.syntax._

case object FileSystem {
  def readFile(blocker: Blocker, filePath: Path)(implicit ctxS: ContextShift[IO]): Stream[IO, String] =
    readAll[IO](filePath, blocker, 4096).through(text.utf8Decode)

  def writeFile(blocker: Blocker, filePath: Path, content: String)(implicit ctxS: ContextShift[IO]): IO[Unit] =
    Stream.emit[IO, String](content)
      .through(text.utf8Encode)
      .through(writeAll[IO](filePath, blocker)
    ).compile.drain

  def createDirectoryIfNotExist(blocker: Blocker, storeDirectory: Path)(implicit ctxS: ContextShift[IO]): IO[Unit] =
    exists[IO](blocker, storeDirectory).flatMap(exists => {
      if(!exists) {
        createDirectory[IO](blocker, storeDirectory).map(_ => ())
      } else {
        IO.unit
      }
    })

  def streamRecordings(blocker: Blocker, path: Path)(implicit ctx: ContextShift[IO]): Stream[IO, Recording] = readAll[IO](path, blocker, 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .evalMap(content => IO.fromEither(decode[HistoryRecording](content)))
      .evalMap((res: HistoryRecording) => {
        val fileContent = Blocker[IO].use(
          FileSystem.readFile(_, new File(path.getParent.getFileName.toString ++ "/" ++ res.fileName).toPath)
            .through(text.lines)
            .compile
            .fold("")(_ ++ _))

        fileContent.map(Recording(res.at, res.callbackUrl, res.url, _))
      })

  def persistToDisk(historyName: String, genFileName: IO[String], storeDirectory: String, blocker: Blocker)(res: EnqueueScrapeResult)(implicit ctx: ContextShift[IO]): IO[Unit] =
    for {
      fileName <- genFileName
      _ <- FileSystem.writeFile(blocker, new File(storeDirectory ++ fileName).toPath, res.html)
      _ <- Resource.fromAutoCloseable(IO(new FileWriter(storeDirectory ++ historyName, true))).use(
        fw => blocker.blockOn(IO(fw.append(HistoryRecording(
          at = res.time,
          url = res.request.url,
          fileName = fileName,
          callbackUrl = res.request.callbackUrl
        ).asJson.noSpaces ++ "\n"))))
    } yield ()
}
