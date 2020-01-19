package algimk.config

case class HttpConfig(port: Int)

sealed trait DriverConfig {
  def driverLocation: String
}

final case class User(username: String, password: String)

final case class FirefoxConfig(driverLocation: String) extends DriverConfig
final case class ChromeConfig(driverLocation: String) extends DriverConfig

final case class QueueBounds(linkQueueBound: Int, parseQueueBound: Int)

final case class Config(http: HttpConfig,
                  subscribers: List[String],
                  storeDirectory: String,
                  users: List[User],
                  browserDrivers: List[DriverConfig],
                  queueBounds: QueueBounds,
                  proxyConfigFileName: Option[String],
                  secretKey: String)
