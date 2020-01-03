package algimk.config

import cats.effect.IO
import hammock.InterpTrans
import hammock.asynchttpclient.AsyncHttpClientInterpreter
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
  implicit val interpreter: InterpTrans[IO] = AsyncHttpClientInterpreter.instance[IO]
  implicit val decodeProxyConfig: Decoder[ProxyConfig] = deriveDecoder[ProxyConfig]
  implicit val encodeProxyConfig: Encoder[ProxyConfig] = deriveEncoder[ProxyConfig]

  def getProxies: IO[List[ProxyConfig]] = {
    import hammock._

    Hammock.request(Method.GET, uri"https://api.proxyscrape.com/".params(List(
      "request" -> "getproxies",
      "proxytype" -> "http",
      "timeout" -> "500",
      "country" -> "all",
      "ssl" -> "yes",
      "anonymity" -> "elite"
    ): _*),
      Map.empty,
    ).exec[IO].map(_.entity.cata[String](
      _.content.toString(),
      ctx => new String(ctx.content),
      _ => ""
    )).map(_.split("\r\n").map(SSLProxy).toList)
  }
}