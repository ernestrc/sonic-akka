package build.unstable.sonic.server.system

import java.net.InetAddress
import java.util.UUID

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.stream.actor._
import build.unstable.sonic.Exceptions.ProtocolException
import build.unstable.sonic.JsonProtocol._
import build.unstable.sonic.model._
import build.unstable.sonic.server.ServerLogging
import build.unstable.tylog.Variation
import org.reactivestreams._
import org.slf4j.event.Level

import scala.util.Failure
import scala.util.control.NonFatal

class WsHandler(controller: ActorRef, clientAddress: Option[InetAddress]) extends ActorPublisher[SonicMessage]
  with ActorSubscriber with ServerLogging with Stash {

  import akka.stream.actor.ActorPublisherMessage._
  import akka.stream.actor.ActorSubscriberMessage._

  override protected def requestStrategy: RequestStrategy = OneByOneRequestStrategy

  case object UpstreamCompleted

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case e: Exception ⇒
      log.error(e, "error in publisher")
      self ! Failure(e)
      Stop
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    log.debug("starting ws handler in path {}", self.path)
  }


  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    log.debug("stopped ws handler in path '{}'", self.path)
  }

  override def unhandled(message: Any): Unit = {
    log.warning("message not handled {}", message)
    super.unhandled(message)
  }


  /* HELPERS */

  def requestTil(): Unit = {
    //make sure that requested is never > than totalDemand
    while (pendingToStream < totalDemand) {
      subscription.request(1)
      pendingToStream += 1L
    }
  }

  def setInitState(): Unit = {
    pendingToStream = 0L
    traceId = UUID.randomUUID().toString
    subscription = null
    waitingFor = null
    handler = null
  }

  def restartInternally(): Unit = {
    setInitState()
    unstashAll()
    context.become(receive)
  }

  val subs = new Subscriber[SonicMessage] {

    override def onError(t: Throwable): Unit = {
      log.error(t, "publisher called onError of wsHandler")
      self ! Failure(t)
    }

    override def onSubscribe(s: Subscription): Unit = self ! s

    override def onComplete(): Unit = self ! UpstreamCompleted

    override def onNext(t: SonicMessage): Unit = self ! t

  }


  /* STATE */

  var pendingToStream: Long = _
  var traceId: String = _
  var subscription: StreamSubscription = _
  var waitingFor: CallType = _
  var handler: ActorRef = _

  setInitState()


  /* BEHAVIOUR */

  def stashCommands: Receive = {
    case OnNext(i: SonicCommand) ⇒ stash()
  }

  def commonBehaviour: Receive = {

    case UpstreamCompleted ⇒
      val msg = "completed stream without done msg"
      val e = new ProtocolException(msg)
      log.error(e, msg)
      context.become(completing(StreamCompleted.error(traceId, e)))

    case msg@OnError(e) ⇒
      log.error(e, "ws stream error")
      onErrorThenStop(e)
  }

  // 4
  def completing(done: StreamCompleted): Receive = stashCommands orElse {
    log.debug("completing with ev {}", done)
    val recv: Receive = {
      case ActorPublisherMessage.Cancel | ActorSubscriberMessage.OnComplete ⇒
        log.debug("completing: received Cancel || OnComplete {}", done.traceId)
        onComplete()
        context.stop(self)
      case UpstreamCompleted ⇒ //expected, overrides commonBehaviour
        log.debug("completing: UpstreamCompleted {}", done.traceId)
      case OnNext(ClientAcknowledge) ⇒
        log.debug("completing: received ack {}", done.traceId)
        restartInternally()
      //this can only happen if cancel was sent between props and subscription
      case s: Subscription ⇒ s.cancel()
        log.debug("completing: received subscription {}: {}", s, done.traceId)
    }

    if (isActive && totalDemand > 0) {
      log.debug("completing stream with ev {}", done)
      onNext(done)
      stashCommands orElse recv orElse commonBehaviour orElse {
        case Request(n) ⇒ //ignore
          log.debug("client request of {} but waiting for ack...", n)
      }
    } else stashCommands orElse recv orElse commonBehaviour orElse {
      case Request(n) =>
        log.debug("Request: completing stream with ev {}", done)
        onNext(done)
        context.become(stashCommands orElse recv orElse commonBehaviour)
    }
  }

  // 3
  def materialized: Receive = stashCommands orElse commonBehaviour orElse {

    case ActorSubscriberMessage.OnComplete | ActorPublisherMessage.Cancel ⇒
      // its polite to cancel first before stopping
      subscription.cancel()
      //will succeed
      onComplete()
      context.stop(self)

    case OnNext(CancelStream) ⇒
      subscription.cancel() //cancel source
      context.become(completing(StreamCompleted.success(traceId)))

    case msg@Request(n) ⇒ requestTil()

    case Failure(e) ⇒ context.become(completing(StreamCompleted.error(traceId, e)))

    case s: StreamCompleted ⇒ context.become(completing(s))

    case msg: SonicMessage ⇒
      try {
        if (isActive) {
          onNext(msg)
          pendingToStream -= 1L
        }
        else log.warning("dropping message {}: wsHandler is not active", msg)
      } catch {
        case e: Exception ⇒
          log.error(e, "error onNext: pending: {}; demand: {}", pendingToStream, totalDemand)
          context.become(completing(StreamCompleted.error(traceId, e)))
      }
  }

  // 2
  def waiting(traceId: String): Receive = stashCommands orElse commonBehaviour orElse {
    case r: Request ⇒ //ignore for now

    case ActorPublisherMessage.Cancel | ActorSubscriberMessage.OnComplete ⇒
      val msg = "client completed/canceled stream while waiting for source materialization"
      val e = new Exception(msg)
      log.tylog(Level.INFO, traceId, waitingFor, Variation.Failure(e), msg)
      // we dont need to worry about cancelling subscription here
      // because terminating this actor will stop the source actor
      onComplete()
      context.stop(self)

    case OnNext(CancelStream) ⇒ context.become(completing(StreamCompleted.success(traceId)))

    case handlerProps: Props ⇒
      log.debug("received props {}", handlerProps)
      handler = context.actorOf(handlerProps)
      val pub = ActorPublisher[SonicMessage](handler)
      pub.subscribe(subs)

    //auth cmd succeded
    case token: String ⇒
      log.tylog(Level.INFO, traceId, waitingFor, Variation.Success, "received new token '{}'", token)
      onNext(OutputChunk(Vector(token)))
      context.become(completing(StreamCompleted.success(traceId)))

    //auth cmd failed
    case Failure(e) ⇒
      log.tylog(Level.INFO, traceId, waitingFor, Variation.Failure(e), "failed to process new command")
      context.become(completing(StreamCompleted.error(traceId, e)))

    case s: Subscription ⇒
      log.tylog(Level.INFO, traceId, waitingFor, Variation.Success, "materialized source and subscribed to it")
      subscription = new StreamSubscription(s)
      requestTil()
      context.become(materialized)

  }

  // 1
  def receive: Receive = commonBehaviour orElse {
    case r: Request ⇒ //ignore for now

    case ActorPublisherMessage.Cancel ⇒
      log.debug("client completed stream before sending a command")
      context.stop(self)

    case ActorSubscriberMessage.OnComplete ⇒
      log.debug("client completed stream before sending a command")
      onCompleteThenStop()

    case OnNext(i: SonicCommand) ⇒
      val withTraceId = {
        i.traceId match {
          case Some(id) ⇒
            traceId = id
            i
          case None ⇒ i.setTraceId(traceId)
        }
      }

      // for logging
      waitingFor = withTraceId match {
        case q: Query ⇒ MaterializeSource
        case a: Authenticate ⇒ GenerateToken
      }

      log.tylog(Level.INFO, withTraceId.traceId.get, waitingFor,
        Variation.Attempt, "deserialized command {}", withTraceId)

      controller ! NewCommand(withTraceId, clientAddress)
      context.become(waiting(withTraceId.traceId.get))

    case OnNext(msg) ⇒
      val msg = "first message should be a SonicCommand"
      val e = new ProtocolException(msg)
      log.error(e, msg)
      context.become(completing(StreamCompleted.error(traceId, e)))

  }
}
