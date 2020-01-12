package algimk

import algimk.model.{EnqueueRequest, Recording}
import cats.effect.{ContextShift, Timer, IO}
import fs2.Stream
import org.http4s.{HttpRoutes, Response, Status}
import org.http4s.server.blaze.BlazeServerBuilder
import model._
import org.http4s.dsl.io.OptionalQueryParamDecoderMatcher
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.dsl.io._
import org.http4s.syntax.kleisli._
import cats.effect.Timer

object ScrappyServer {
  object OptionalTokenParamMatcher extends OptionalQueryParamDecoderMatcher[String]("token")

  def create(recordLink: EnqueueRequest => IO[Unit], port: Option[Int] = None, serverToken: Option[String] = None,
             recordingStream: Stream[IO, Recording])(implicit tm: Timer[IO], ctx: ContextShift[IO]): BlazeServerBuilder[IO] = {

    BlazeServerBuilder[IO]
      .bindHttp(port.getOrElse(0))
      .withHttpApp(HttpRoutes.of[IO] {
        case req@POST -> Root / "enqueue" :? OptionalTokenParamMatcher(token) =>
          if (serverToken.forall(tk => token.contains(tk))) {
            req.as[EnqueueRequest]
              .flatMap(body =>
                recordLink(body).map(_ => Response(Status.Ok))
              )
          } else IO.pure(Response(Unauthorized))
        case GET -> Root / "replay" => Ok(recordingStream)
      }.orNotFound)
  }
}
