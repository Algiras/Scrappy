package algimk.model

import cats.effect.IO
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.http4s.{EntityDecoder, EntityEncoder}
import org.http4s.circe.{jsonEncoderOf, jsonOf}

case class EnqueueRequest(url: String, callbackUrl: Option[String])
case class EnqueueRetryRequest(url: String, callbackUrl: Option[String], retryCount: Int)

object EnqueueRequest {
  implicit private val enqueueEncoder: Encoder[EnqueueRequest] = deriveEncoder
  implicit private val enqueueDecoder: Decoder[EnqueueRequest] = deriveDecoder

  implicit val enqueueRequestEntityDecoder: EntityDecoder[IO, EnqueueRequest] = jsonOf[IO, EnqueueRequest]
  implicit val enqueueRequestEntityEncoder: EntityEncoder[IO, EnqueueRequest] = jsonEncoderOf[IO, EnqueueRequest]
}