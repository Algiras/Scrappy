package algimk

import algimk.model.{EnqueueRequest, Recording}
import cats.data.OptionT
import cats.effect.{ContextShift, IO, Timer}
import fs2.Stream
import org.http4s.server.blaze.BlazeServerBuilder
import model._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.dsl.io._
import org.http4s.syntax.kleisli._
import cats.effect.Timer
import cats.effect.IO
import io.chrisdavenport.fuuid.FUUID
import org.http4s.{EntityDecoder, EntityEncoder, Http, HttpRoutes, Response, Status}
import cats.implicits._
import io.circe.Decoder
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import io.circe.{Decoder, Encoder}
import cats.effect.IO
import org.http4s.dsl.io._
import tsec.authentication._
import tsec.common.SecureRandomId
import org.http4s.server.middleware._
import cats.~>

import scala.concurrent.duration._
import tsec.passwordhashers.PasswordHash
import tsec.passwordhashers.jca.BCrypt
import cats.effect.IO
import cats.effect.concurrent.Ref

object ScrappyServer {

  implicit private val userLoginDecoder: Decoder[UserLogin] = deriveDecoder
  implicit private val userLoginEncoder: Encoder[UserLogin] = deriveEncoder

  implicit val userLoginEntityDecoder: EntityDecoder[IO, UserLogin] = jsonOf[IO, UserLogin]
  implicit val userLoginEntityEncoder: EntityEncoder[IO, UserLogin] = jsonEncoderOf[IO, UserLogin]

  case class AuthUser(username: String, password: PasswordHash[BCrypt])

  case class UserLogin(username: String, password: String)

  def create(recordLink: EnqueueRetryRequest => IO[Unit], port: Option[Int] = None, users: Map[FUUID, AuthUser] = Map.empty, bearerTokens: Ref[IO, Map[SecureRandomId, TSecBearerToken[FUUID]]],
             recordingStream: Stream[IO, Recording], retryCount: Option[PositiveNumber], secretKey: String)(implicit tm: Timer[IO], ctx: ContextShift[IO]): BlazeServerBuilder[IO] = {

    val userStore: IdentityStore[IO, FUUID, AuthUser] = new IdentityStore[IO, FUUID, AuthUser] {
      override def get(id: FUUID): OptionT[IO, AuthUser] = OptionT(users.get(id).pure[IO])
    }

    val bearerTokenStore: BackingStore[IO, SecureRandomId, TSecBearerToken[FUUID]] = new BackingStore[IO, SecureRandomId, TSecBearerToken[FUUID]] {
      override def put(elem: TSecBearerToken[FUUID]): IO[TSecBearerToken[FUUID]] = bearerTokens.modify(tk => {
        (tk + (elem.id -> elem), elem)
      })

      override def update(v: TSecBearerToken[FUUID]): IO[TSecBearerToken[FUUID]] = bearerTokens.modify(tk => {
        val filteredTokens: Map[SecureRandomId, TSecBearerToken[FUUID]] = tk.filterNot(_._2 == v)
        val result = filteredTokens + (v.id -> v)
        (result, v)
      })

      override def delete(id: SecureRandomId): IO[Unit] = bearerTokens.update(_ - id)

      override def get(id: SecureRandomId): OptionT[IO, TSecBearerToken[FUUID]] = OptionT(bearerTokens.get.map(_.get(id)))
    }

    val settings: TSecTokenSettings = TSecTokenSettings(
      expiryDuration = 10.minutes,
      maxIdle = None
    )


    val bearerTokenAuth = BearerTokenAuthenticator(
      bearerTokenStore,
      userStore,
      settings
    )

    val Auth = SecuredRequestHandler(bearerTokenAuth)

    val routes: HttpRoutes[IO] = if (users.isEmpty) {
      HttpRoutes.of[IO] {
        case req@POST -> Root / "enqueue" => req.as[EnqueueRequest]
          .flatMap(body => recordLink(
            EnqueueRetryRequest(body.url, body.callbackUrl, retryCount.map(_.value).getOrElse(0))
          ).map(_ => Response(Status.Ok)))
        case GET -> Root / "replay" => Ok(recordingStream)
      }
    } else {
      val loginRoute = HttpRoutes.of[IO] {
        case req@POST -> Root / "login" => req.as[UserLogin].flatMap(user => for {
          usr <- IO.fromEither(users.find(usr => usr._2.username == user.username).toRight(new RuntimeException("User not found")))
          hash <- BCrypt.hashpw[IO](user.password)
          _ <- BCrypt.checkpwBool[IO](usr._2.password, hash).flatMap{
            case true => IO.pure(())
            case false => IO.raiseError(new RuntimeException("Password invalid"))
          }
          token <- Auth.authenticator.create(usr._1).flatMap(Auth.authenticator.refresh(_))
          res <- Ok(token.id.toString).map(Auth.authenticator.embed(_, token))
        } yield res).attempt.map {
          case Right(value) => value
          case Left(error) => Response[IO](Status.Forbidden)
        }
      }

      val secureRoutes: TSecAuthService[AuthUser, TSecBearerToken[FUUID], IO] = TSecAuthService {
        case authReq@POST -> Root / "enqueue" asAuthed user => authReq.request.as[EnqueueRequest]
          .flatMap(body => recordLink(
            EnqueueRetryRequest(
              body.url,
              body.callbackUrl,
              retryCount.map(_.value).getOrElse(0)
            )
          ).map(_ => Response(Status.Ok)))
        case GET -> Root / "replay" asAuthed user => Ok(recordingStream)
      }

      loginRoute <+> Auth.liftService(secureRoutes)
    }

    val transform = new (IO ~> IO){
      override def apply[A](fa: IO[A]): IO[A] = fa
    }

    val logger: Http[IO, IO] => Http[IO, IO] = Logger[IO, IO](logHeaders = true, logBody = true, transform)

    BlazeServerBuilder[IO].bindHttp(port.getOrElse(0)).withHttpApp(logger(routes.orNotFound))
  }
}
