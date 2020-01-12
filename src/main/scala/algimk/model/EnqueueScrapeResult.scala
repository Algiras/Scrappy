package algimk.model

import org.joda.time.DateTime

case class EnqueueScrapeResult(request: EnqueueRequest, html: String, time: DateTime)