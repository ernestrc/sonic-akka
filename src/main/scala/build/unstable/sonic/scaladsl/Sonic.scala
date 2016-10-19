package build.unstable.sonic.scaladsl

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID

import akka.actor.{ActorSystem, Cancellable, Props}
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import build.unstable.sonic.client.{SonicPublisher, SonicSupervisor}
import build.unstable.sonic.model._
import spray.json.{JsArray, JsString}

import scala.concurrent.Future

object Sonic {

  private[unstable] def collectToken(traceId: String) = { messages: Vector[SonicMessage] ⇒
    messages.collectFirst {
      case OutputChunk(JsArray(Vector(JsString(token)))) ⇒ Future.successful(token)
    }.getOrElse {
      Future.failed(
        new SonicPublisher.StreamException(traceId, new Exception("protocol error: no token found in stream")))
    }
  }

  private[unstable] def sonicSupervisorProps(addr: InetSocketAddress): Props =
    Props(classOf[SonicSupervisor], addr)

  //length prefix framing
  private[unstable] def lengthPrefixEncode(bytes: ByteString): ByteString = {
    val len = ByteBuffer.allocate(4)
    len.putInt(bytes.length)
    ByteString(len.array() ++ bytes)
  }

  private[unstable] val fold = Sink.fold[Vector[SonicMessage], SonicMessage](Vector.empty[SonicMessage])((a, e) ⇒ a :+ e)

  case class Client(address: InetSocketAddress)(implicit system: ActorSystem, mat: Materializer) {

    val supervisor = system.actorOf(Sonic.sonicSupervisorProps(address))


    final def stream(cmd: SonicCommand): Source[SonicMessage, Cancellable] = {

      val publisher: Props = Props(classOf[SonicPublisher], supervisor, cmd, true)
      val ref = system.actorOf(publisher)

      Source.fromPublisher(ActorPublisher[SonicMessage](ref))
        .mapMaterializedValue { _ ⇒
          new Cancellable {
            private var cancelled = false

            override def isCancelled: Boolean = cancelled

            override def cancel(): Boolean = {
              if (!cancelled) {
                ref ! CancelStream
                cancelled = true
                cancelled
              } else false
            }
          }
        }
    }

    final def run(cmd: SonicCommand): Future[Vector[SonicMessage]] = stream(cmd).toMat(fold)(Keep.right).run()

    final def authenticate(user: String, apiKey: String, traceId: String = UUID.randomUUID().toString)
                          (implicit system: ActorSystem, mat: ActorMaterializer): Future[String] = {

      import system.dispatcher

      stream(Authenticate(user, apiKey, Some(traceId)))
        .toMat(fold)(Keep.right)
        .run()
        .flatMap(collectToken(traceId))
    }
  }

}
