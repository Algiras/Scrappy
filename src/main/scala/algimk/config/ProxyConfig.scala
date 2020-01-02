package algimk.config

import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

sealed trait ProxyConfig
final case class HttpProxy(url: String) extends ProxyConfig
final case class SSLProxy(url: String) extends ProxyConfig
final case class FTPProxy(url: String) extends ProxyConfig
final case class AutoConfigProxy(url: String) extends ProxyConfig
final case class Socks4Proxy(url: String) extends ProxyConfig
final case class Socks5Proxy(url: String) extends ProxyConfig

object ProxyConfig {
  implicit val decodeProxyConfig: Decoder[ProxyConfig] = deriveDecoder[ProxyConfig]
  implicit val encodeProxyConfig: Encoder[ProxyConfig] = deriveEncoder[ProxyConfig]
}