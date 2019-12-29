package algimk.config

case class HttpConfig(port: Int)

case class Drivers(firefox: String)

case class QueueBounds(linkQueueBound: Int, parseQueueBound: Int)

case class Config(http: HttpConfig,
                  subscribers: List[String],
                  storeDirectory: String,
                  token: Option[String],
                  drivers: Drivers,
                  queueBounds: QueueBounds)
