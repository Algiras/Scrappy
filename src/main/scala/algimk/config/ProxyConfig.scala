package algimk.config

import algimk.FileSystem
import cats.effect.{Blocker, ContextShift, IO, Resource}
import cats.implicits._
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.implicits._
import io.circe.parser.decode
import java.nio.file._

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

  def readProxies(blocker: Blocker, filePathOpt: Option[Path])(implicit contextShift: ContextShift[IO]): IO[List[ProxyConfig]] = filePathOpt.map(filePath =>
    FileSystem.readFile(blocker, filePath).compile.fold("")(_ ++ _).map(content => decode[List[ProxyConfig]](content)).rethrow
  ).getOrElse(IO(List.empty[ProxyConfig]))

  def getProxyScrapeProxies(implicit ctxS: ContextShift[IO]): IO[List[SSLProxy]] = {
    Blocker[IO].use(blocker =>
      BlazeClientBuilder[IO](blocker.blockingContext).resource.flatMap(client => Resource.liftF(client.expect[String](
        uri"https://api.proxyscrape.com/"
          .withQueryParam("request", "getproxies")
          .withQueryParam("proxytype", "http")
          .withQueryParam("timeout", "500")
          .withQueryParam("country", "all")
          .withQueryParam("ssl", "yes")
      ))).use(res => IO(res.split("\r\n").map(SSLProxy).toList))
    )
  }
}