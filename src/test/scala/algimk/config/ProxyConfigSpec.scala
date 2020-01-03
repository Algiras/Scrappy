package algimk.config

import cats.effect.{ContextShift, IO}
import org.specs2.mutable.Spec

import scala.concurrent.ExecutionContext

class ProxyConfigSpec(implicit val executionContext: ExecutionContext) extends Spec {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(executionContext)

  "ProxyConfig" should {
    "get list of proxies" in {
        ProxyConfig.getProxyScrapeProxies.unsafeRunSync() must not(beEmpty)
    }
  }
}
