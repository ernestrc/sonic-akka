package build.unstable.sonic.examples

import java.net.InetSocketAddress
import java.util.UUID

import akka.actor.ActorDSL._
import akka.actor._
import akka.io.{IO, Tcp}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import build.unstable.sonic.model._
import build.unstable.sonic.scaladsl.Sonic
import build.unstable.sonic.server.source.SyntheticPublisher
import build.unstable.sonic.server.system.TcpSupervisor
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object SonicServer {
  val ADDR = new InetSocketAddress("127.0.0.1", 10001)
}

class SonicServer(implicit system: ActorSystem) {
  // obtain a reference to the IO manager actor for Tcp
  val tcpIoService: ActorRef = IO(Tcp)

  // create a new controller
  // which needs to handle NewCommand
  val controller: ActorRef = actor(new Act {
    become {
      // tcp handler expects a reply with token: String or Failure(e)
      case NewCommand(Authenticate(user, key, _), addr) ⇒ sender() ! "aValidToken"
      // tcp handler expects reply with the Props of the Publisher or Failure(e)
      case NewCommand(q: Query, addr) ⇒
        // publish 100 randomly generated integers
        val n = 100
        val props = Props(classOf[SyntheticPublisher],
          None, Some(n), 10, q.query, false, None, RequestContext(q.traceId.get, None))
        sender() ! props
    }
  })

  val tcpService = system.actorOf(Props(classOf[TcpSupervisor], controller))

  val bind = Tcp.Bind(tcpService, SonicServer.ADDR, options = Nil, pullMode = true)

  tcpIoService.tell(bind, tcpService)
}

/**
  * This example makes use of `sonicd-core` artifact which provides a streaming and futures APIs to build
  * a Sonic endpoint and query it with a client.
  */
object ScalaExample extends App {

  implicit val system = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(system))

  // instantiate server
  val server = new SonicServer()

  // our server ignores configuration
  // but tipically here we can pass configuration parameters
  val config: JsObject = JsObject.empty

  // generate a traceId
  val traceId = UUID.randomUUID().toString

  // instantiate client, which will allocate resources to query our sonic endpoint
  val client = Sonic.Client(SonicServer.ADDR)

  // Akka Streams API
  {
    val query = Query("blabla", config, traceId, None)

    val source = client.stream(query)
    val sink = Sink.ignore
    val res: Cancellable = Source.fromGraph(source).to(sink).run()

    res.cancel()

    assert(res.isCancelled)
  }

  // Futures API
  {
    val query = Query("blabla", config, traceId, None)

    val res: Future[Vector[SonicMessage]] = client.run(query)

    val done = Await.result(res, 20.seconds)
    // server configures SyntheticPublisher to always return 100 dps
    // 1 started + 1 metadata + 100 QueryProgress + 100 OutputChunk + 1 DoneWithQueryExecution
    assert(done.length == 203)
  }

  system.terminate()

}
