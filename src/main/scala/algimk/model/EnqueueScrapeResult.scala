package algimk.model

import org.joda.time.DateTime

case class EnqueueScrapeResult(request: EnqueueRetryRequest, html: String, time: DateTime)