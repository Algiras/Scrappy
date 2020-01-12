package algimk.model

import io.circe.{Decoder, Encoder}
import org.joda.time.DateTime
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

case class HistoryRecording(at: DateTime, url: String, fileName: String, callbackUrl: Option[String])

object HistoryRecording {
  implicit val historyRecordingEncoder: Encoder[HistoryRecording] = deriveEncoder
  implicit val historyRecordingDecoder: Decoder[HistoryRecording] = deriveDecoder
}