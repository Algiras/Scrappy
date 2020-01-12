package algimk.model

import cats.effect.IO
import io.circe.{Decoder, Encoder}
import org.joda.time.DateTime
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.http4s.{EntityDecoder, EntityEncoder}
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import org.http4s._

case class Recording(at: DateTime, callbackUrl: Option[String], url: String, html: String)

object Recording {
  implicit val recordingEncoder: Encoder[Recording] = deriveEncoder
  implicit val recordingDecoder: Decoder[Recording] = deriveDecoder

  implicit val recordingRequestEntityDecoder: EntityDecoder[IO, Recording] = jsonOf[IO, Recording]
  implicit val recordingRequestEntityEncoder: EntityEncoder[IO, Recording] = jsonEncoderOf[IO, Recording]
}