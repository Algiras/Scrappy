package algimk

import io.circe.Decoder.Result
import io.circe.{Decoder, Encoder, HCursor, Json}
import org.joda.time.DateTime

package object model {
  implicit val timeEncoder: Encoder[DateTime] = new Encoder[DateTime] {
    override def apply(a: DateTime): Json = Encoder[Long].apply(a.getMillis)
  }
  implicit val timeDecoder: Decoder[DateTime] = new Decoder[DateTime] {
    override def apply(c: HCursor): Result[DateTime] = Decoder[Long].apply(c).map(ml => new DateTime(ml))
  }
}
