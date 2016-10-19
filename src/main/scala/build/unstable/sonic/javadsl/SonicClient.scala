package build.unstable.sonic.javadsl

import java.net.InetSocketAddress
import java.util.concurrent.{Future, TimeUnit}
import java.util.{Optional, UUID}

import akka.actor.{ActorSystem, Cancellable}
import akka.stream.javadsl._
import akka.stream.{Materializer, scaladsl}
import build.unstable.sonic.model.{AuthConfig, Authenticate, SonicCommand, SonicMessage}
import build.unstable.sonic.scaladsl.Sonic
import spray.json._

import scala.collection.JavaConversions
import scala.concurrent.Await
import scala.concurrent.duration.Duration

private[unstable] object SonicClient {
  def convertToJava[T](x: scala.concurrent.Future[T], cancelFn: Boolean ⇒ Boolean): Future[T] =
    new Future[T] {

      private var _isCancelled = false

      override def isCancelled: Boolean = throw new UnsupportedOperationException

      override def get(): T = Await.result(x, Duration.Inf)

      override def get(timeout: Long, unit: TimeUnit): T = Await.result(x, Duration.create(timeout, unit))

      override def cancel(mayInterruptIfRunning: Boolean): Boolean =
        if (!_isCancelled) {
          _isCancelled = true
          cancelFn(mayInterruptIfRunning)
        } else false

      override def isDone: Boolean = x.isCompleted
    }
}

class Query(query: String, auth: Optional[AuthConfig], traceId: Optional[String], config: java.util.Map[String, JsValue])
  extends build.unstable.sonic.model.Query(None, if (traceId.isPresent) Some(traceId.get) else None,
    if (auth.isPresent) Some(auth.get()) else None, query, JsObject(JavaConversions.mapAsScalaMap(config).toMap))

final class SonicClient(address: InetSocketAddress, system: ActorSystem, materializer: Materializer) {

  private val _client = Sonic.Client(address)(system, materializer)

  def stream(cmd: SonicCommand): Source[SonicMessage, Cancellable] = _client.stream(cmd).asJava


  def run(cmd: SonicCommand): Future[java.util.Collection[SonicMessage]] = {
    import system.dispatcher

    val (cancellable, future) = Source.fromGraph(stream(cmd)).asScala
      .toMat(Sonic.fold)(scaladsl.Keep.both).run()(materializer)

    SonicClient.convertToJava(_client.run(cmd).map(t ⇒ JavaConversions.asJavaCollection(t)),
      mayInterrupt ⇒ (mayInterrupt || future.isCompleted) && cancellable.cancel()
    )
  }


  def authenticate(user: String, apiKey: String, traceId: String = UUID.randomUUID().toString): Future[String] = {
    import system.dispatcher

    val (cancellable, future) = Source.fromGraph(stream(Authenticate(user, apiKey, Some(traceId)))).asScala
      .toMat(Sonic.fold)(scaladsl.Keep.both)
      .run()(materializer)

    SonicClient.convertToJava(future.flatMap(Sonic.collectToken(traceId)),
      mayInterrupt ⇒ (mayInterrupt || future.isCompleted) && cancellable.cancel()
    )
  }
}
