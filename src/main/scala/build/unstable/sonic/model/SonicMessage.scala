package build.unstable.sonic.model

import java.nio.charset.Charset

import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.util.ByteString
import build.unstable.sonic.JsonProtocol._
import build.unstable.sonic._
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import spray.json._

import scala.util.Try

sealed trait SonicMessage {

  val variation: Option[String]

  val payload: Option[JsValue]

  val eventType: String

  @transient
  lazy val json: JsValue = {
    val fields = scala.collection.mutable.ListBuffer(
      SonicMessage.eventType → (JsString(eventType): JsValue))

    variation.foreach(v ⇒ fields.append(SonicMessage.variation → JsString(v)))
    payload.foreach(p ⇒ fields.append(SonicMessage.payload → p))

    JsObject(fields.toMap)
  }

  def toBytes: ByteString = {
    val bytes = json.compactPrint.getBytes(Charset.forName("utf-8"))
    ByteString(bytes)
  }

  def toWsMessage: Message =
    TextMessage.Strict(json.compactPrint)

  def isCompleted: Boolean = this.isInstanceOf[StreamCompleted]
}

//events sent by the server to the client
case class TypeMetadata(typesHint: Vector[(String, JsValue)]) extends SonicMessage {
  val variation: Option[String] = None
  val payload: Option[JsValue] = Some(typesHint.toJson)
  val eventType: String = SonicMessage.meta
}

case class OutputChunk(data: JsArray) extends SonicMessage {
  val variation: Option[String] = None
  val eventType = SonicMessage.out
  val payload: Option[JsValue] = Some(data.toJson)
}

object OutputChunk {
  def apply[T: JsonWriter](data: Vector[T]): OutputChunk = OutputChunk(JsArray(data.map(_.toJson)))
}

case class QueryProgress(status: QueryProgress.Status, progress: Double,
                         total: Option[Double], units: Option[String]) extends SonicMessage {
  val payload: Option[JsValue] = Some(JsObject(Map(
    "p" → JsNumber(progress),
    "s" → JsNumber(status),
    "t" → total.map(JsNumber.apply).getOrElse(JsNull),
    "u" → units.map(JsString.apply).getOrElse(JsNull)
  )))
  val variation = None
  override val eventType = SonicMessage.progress
}

object QueryProgress {
  type Status = Int
  val Queued = 0
  val Started = 1
  val Running = 2
  val Waiting = 3
  val Finished = 4
}

case class StreamStarted(traceId: String) extends SonicMessage {
  override val variation: Option[String] = Some(traceId)
  override val payload: Option[JsValue] = None
  override val eventType: String = SonicMessage.started
}

case class StreamCompleted(traceId: String, error: Option[Throwable] = None) extends SonicMessage {

  val success = error.isEmpty

  override val eventType = SonicMessage.completed
  override val variation: Option[String] = error.map(e ⇒ getStackTrace(e))
  override val payload: Option[JsValue] = Some(JsObject("trace_id" → JsString.apply(traceId)))

}

object StreamCompleted {
  def success(traceId: String): StreamCompleted = StreamCompleted(traceId)

  def error(traceId: String, e: Throwable): StreamCompleted = StreamCompleted(traceId, Some(e))

  def success(implicit ctx: RequestContext) = StreamCompleted(ctx.traceId)

  def error(e: Throwable)(implicit ctx: RequestContext): StreamCompleted = StreamCompleted(ctx.traceId, Some(e))
}

//events sent by the client to the server
case object ClientAcknowledge extends SonicMessage {
  override val variation: Option[String] = None
  override val payload: Option[JsValue] = None
  override val eventType: String = SonicMessage.ack
}

sealed trait SonicCommand extends SonicMessage {
  val traceId: Option[String]

  def setTraceId(trace_id: String): SonicCommand
}

case class Authenticate(user: String, key: String, traceId: Option[String])
  extends SonicCommand {
  override val variation: Option[String] = Some(key)
  override val payload: Option[JsValue] = Some(JsObject(Map(
    "user" → JsString(user),
    "trace_id" → traceId.map(JsString.apply).getOrElse(JsNull)
  )))
  override val eventType: String = SonicMessage.auth

  override def setTraceId(trace_id: String): SonicCommand =
    copy(traceId = Some(trace_id))

  override def toString: String = s"Authenticate($user)"
}

case object CancelStream extends SonicMessage {
  override val variation: Option[String] = None
  override val payload: Option[JsValue] = None
  override val eventType: String = SonicMessage.cancel
}

object SonicMessage {

  //fields
  val eventType = "e"
  val variation = "v"
  val payload = "p"

  //messages
  val auth = "H"
  val cancel = "C"
  val started = "S"
  val query = "Q"
  val meta = "T"
  val progress = "P"
  val out = "O"
  val ack = "A"
  val completed = "D"

  def unapply(ev: SonicMessage): Option[(String, Option[String], Option[JsValue])] =
    Some((ev.eventType, ev.variation, ev.payload))

  private def getField[T: JsonFormat](key: String, fields: Map[String, JsValue]): T = {
    val field = fields
      .getOrElse(key, throw new Exception(s"SonicMessage#getField: missing '$key'"))

    try {
      field.convertTo[T]
    } catch {
      case _: Exception ⇒
        throw new Exception(s"SonicMessage#getField: unexpected type for $key: $field")
    }
  }

  private def getOptional[T: JsonFormat](key: String, fields: Map[String, JsValue]): Option[T] = {
    fields.get(key).flatMap { field ⇒
      try {
        field.convertTo[Option[T]]
      } catch {
        case e: Exception ⇒
          throw new Exception(s"SonicMessage#getOptional: unexpected type for $key: $field")
      }
    }
  }

  def fromJson(raw: String): SonicMessage = {
    val fields: Map[String, JsValue] = raw.parseJson.asJsObject.fields

    getField[String](eventType, fields) match {
      case `out` ⇒ OutputChunk(getField[JsArray](payload, fields))
      case `ack` ⇒ ClientAcknowledge
      case `started` ⇒ StreamStarted(getField[String](variation, fields))
      case `cancel` ⇒ CancelStream
      case `auth` ⇒
        val p = getField[Map[String, JsValue]](payload, fields)
        Authenticate(
          getField[String]("user", p),
          getField[String](variation, fields),
          getOptional[String]("trace_id", p))
      case `meta` ⇒
        getField[JsValue](payload, fields) match {
          case d: JsArray ⇒ TypeMetadata(d.convertTo[Vector[(String, JsValue)]])
          case j: JsObject ⇒ TypeMetadata(j.convertTo[Vector[(String, JsValue)]])
          case a ⇒ throw new Exception(s"SonicMessage#fromJson: expecting JsArray or JsObject found $a")
        }
      case `progress` ⇒
        val p = getField[Map[String, JsValue]](payload, fields)
        QueryProgress(getField[Int]("s", p), getField[Double]("p", p),
          getOptional[Double]("t", p), getOptional[String]("u", p))
      case `query` ⇒
        val p = getField[Map[String, JsValue]](payload, fields)
        val traceId = getOptional[String]("trace_id", p)
        val auth = getOptional[AuthConfig]("auth", p)
        new Query(None, traceId, auth, getField[String](variation, fields), p("config"))
      case `completed` ⇒
        val p = getField[Map[String, JsValue]](payload, fields)
        StreamCompleted(getField[String]("trace_id", p),
          getOptional[String](variation, fields).map(fromStackTrace)
        )
      case e ⇒ throw new Exception(s"unexpected event type '$e'")
    }
  }

  def fromBytes(b: ByteString): SonicMessage = fromJson(b.utf8String)
}

class Query(val id: Option[Long],
            val traceId: Option[String],
            val auth: Option[AuthConfig],
            val query: String,
            val config: JsValue)
  extends SonicCommand {

  override def setTraceId(trace_id: String): SonicCommand =
    copy(trace_id = Some(trace_id))

  override val variation: Option[String] = Some(query)
  override val payload: Option[JsValue] = {
    val fields = scala.collection.mutable.Map(
      "config" → config
    )
    auth.foreach(j ⇒ fields.update("auth", j.toJson))
    traceId.foreach(t ⇒ fields.update("trace_id", JsString(t)))
    Some(JsObject(fields.toMap))
  }
  override val eventType: String = SonicMessage.query

  override def toString: String = s"Query(id=$id,trace_id=$traceId)"

  def copy(query_id: Option[Long] = None, trace_id: Option[String] = None) =
    new Query(query_id orElse id, trace_id orElse traceId, auth, query, config)
}

object Query {

  /**
    * Build a Query from a fully specified source configuration 'config'
    */
  def apply(query: String, config: JsObject, auth: Option[AuthConfig]): Query =
    new Query(None, None, auth, query, config)

  def apply(query: String, config: JsObject, traceId: String, auth: Option[AuthConfig]): Query =
    new Query(None, Some(traceId), auth, query, config)

  /**
    * Build a Query from a configuration alias 'config' for the sonicd server to
    * load from its configuration
    */
  def apply(query: String, config: JsString, auth: Option[AuthConfig]): Query =
    new Query(None, None, auth, query, config)

  def apply(query: String, config: JsString, traceId: String, auth: Option[AuthConfig]): Query =
    new Query(None, Some(traceId), auth, query, config)

  def unapply(query: Query): Option[(Option[Long], Option[AuthConfig], String)] =
    Some((query.id, query.auth, query.query))
}
