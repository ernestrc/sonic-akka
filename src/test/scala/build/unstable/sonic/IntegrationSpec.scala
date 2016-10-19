package build.unstable.sonic

import java.net.{InetAddress, InetSocketAddress}

import akka.Done
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.{Keep, Sink}
import akka.testkit.TestKit
import akka.util.Timeout
import build.unstable.sonic.client.SonicPublisher.StreamException
import build.unstable.sonic.model._
import build.unstable.sonic.scaladsl.Sonic
import com.auth0.jwt.{JWTSigner, JWTVerifier}
import org.scalatest._
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * Requires Sonicd instance running locally on 10002
  * Use docker-compose file in scripts dir
  */
class IntegrationSpec extends WordSpec with Matchers with ScalatestRouteTest
  with BeforeAndAfterAll with JsonProtocol {

  import scala.collection.JavaConversions._

  def toJWTClaims(user: String, authorization: Int, mode: String,
                  from: List[String] = List.empty): java.util.Map[String, AnyRef] = {
    val claims = scala.collection.mutable.Map[String, AnyRef](
      "authorization" → authorization.toString,
      "user" → user,
      "mode" → mode.toString
    )

    //encode allowed ips with token
    if (from.nonEmpty) claims.update("from", from.toJson.compactPrint)
    claims
  }

  import Fixture._

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout: Timeout = 15.seconds
  val ctx: RequestContext = RequestContext("IntegrationSpec", None)
  val tcpAddr = new InetSocketAddress("0.0.0.0", 10002)
  val client = Sonic.Client(tcpAddr)

  val H2Url = s"jdbc:h2:mem:IntegrationSpec"
  val H2Config =
    s"""
       | {
       |  "driver" : "$H2Driver",
       |  "url" : "$H2Url",
       |  "class" : "JdbcSource"
       | }
    """.stripMargin.parseJson.asJsObject

  /* is tested with the node bindings
  "sonicd ws api" should {
  }*/

  "sonicd tcp api" should {
    "run a simple query using the tcp api" in {

      val future: Future[Vector[SonicMessage]] = client.run(syntheticQuery)
      val stream: Future[Done] =
        client.stream(syntheticQuery).toMat(Sink.ignore)(Keep.right).run()

      Await.result(stream, 20.seconds)
      val fDone = Await.result(future, 20.seconds)

      fDone.length shouldBe 113 //1 StreamStarted + 1 metadata + 100 QueryProgress + 10 OutputChunk + 1 StreamCompleted
    }

    "run a query against a source that is configured server side" in {

      val syntheticQuery = new Query(None, None, None, "10", JsString("test_server_config"))
      val future: Future[Vector[SonicMessage]] = client.run(syntheticQuery)
      val stream: Future[Done] =
        client.stream(syntheticQuery).toMat(Sink.ignore)(Keep.right).run()

      Await.result(stream, 20.seconds)

      val fDone = Await.result(future, 20.seconds)
      fDone.length shouldBe 113 //1 started + 1 metadata + 100 QueryProgress + 10 OutputChunk + 1 DoneWithQueryExecution
    }

    // scripts/application.conf:
    val authSecret = "very_secret"

    val verifier = new JWTVerifier(authSecret)
    val signer = new JWTSigner(authSecret)

    "authenticate a user" in {

      val future: Future[String] =
        client.authenticate("serrallonga", "1234")

      val token = Await.result(future, 20.seconds)
      assert(!verifier.verify(token).isEmpty)
    }

    "reject a user authentication attempt if apiKey is invalid" in {

      val future: Future[String] = client.authenticate("serrallonga", "INVALID")

      val e = intercept[Throwable] {
        Await.result(future, 20.seconds)
      }
      assert(e.getCause.getMessage.contains("INVALID"))
    }

    "reject a query of a source that requires authentication if user is unauthenticated" in {
      val syntheticQuery = Query("10", JsString("secure_server_config"), None)
      val future: Future[Vector[SonicMessage]] = client.run(syntheticQuery)

      val e = intercept[Throwable] {
        Await.result(future, 20.seconds)
      }
      assert(e.getCause.getMessage.contains("unauthenticated"))
    }

    "accept a query of a source that requires authentication if user is authenticated with at least the sources security level" in {
      val token = SonicdAuth(signer.sign(toJWTClaims("user", 5, "r")))
      val syntheticQuery = Query("10", JsString("secure_server_config"), Some(token))

      val future: Future[Vector[SonicMessage]] = client.run(syntheticQuery)

      val fDone = Await.result(future, 20.seconds)
      fDone.length shouldBe 113
    }

    "reject a query of a source that requires authentication if user is authenticated with a lower authorization level" in {
      val token = SonicdAuth(signer.sign(toJWTClaims("bandit", 4, "r")))
      val syntheticQuery = Query("10", JsString("secure_server_config"), Some(token))
      val future: Future[Vector[SonicMessage]] = client.run(syntheticQuery)

      val e = intercept[StreamException] {
        Await.result(future, 20.seconds)
      }

      assert(e.getCause.getMessage.contains("unauthorized"))
    }

    "accept a query of a source that requires authentication and has a whitelist of ips and user ip is in the list" in {
      val token: AuthConfig = SonicdAuth(signer.sign(toJWTClaims("bandit", 6, "r", "localhost" :: Nil)))
      val syntheticQuery = Query("10", JsString("secure_server_config"), Some(token))
      val future: Future[Vector[SonicMessage]] = client.run(syntheticQuery)

      val fDone = Await.result(future, 20.seconds)
      fDone.length shouldBe 113
    }

    "reject a query of a source that requires authentication and has a whitelist of ips but user ip is not in the list" in {
      val token = SonicdAuth(signer.sign(toJWTClaims("jkerl", 6, "r", "192.168.1.17" :: Nil)))
      val syntheticQuery = Query("10", JsString("secure_server_config"), Some(token))
      val future: Future[Vector[SonicMessage]] = client.run(syntheticQuery)

      intercept[Throwable] {
        Await.result(future, 20.seconds)
      }
    }

    "should bubble exception thrown by source" in {
      val query = Query("select * from nonesense", H2Config, None) //table nonesense doesn't exist

      val future: Future[Vector[SonicMessage]] =
        client.run(query)

      val stream: Future[Done] =
        client.stream(query).toMat(Sink.ignore)(Keep.right).run()

      val sThrown = intercept[StreamException] {
        Await.result(stream, 20.seconds)
      }
      assert(sThrown.getCause.toString.contains("not found"))

      val thrown = intercept[StreamException] {
        Await.result(future, 20.seconds)
      }
      assert(thrown.getCause.toString.contains("not found"))
    }

    "error message should contain traceId" in {

      val traceID = "coolTraceId"

      val query = Query("select * from nonesense", H2Config, traceID, None) //table nonesense doesn't exist

      val future: Future[Vector[SonicMessage]] =
        client.run(query)

      val stream: Future[Done] =
        client.stream(query).toMat(Sink.ignore)(Keep.right).run()

      val sThrown = intercept[StreamException] {
        Await.result(stream, 20.seconds)
      }
      assert(sThrown.getMessage.contains(traceID))

      val thrown = intercept[StreamException] {
        Await.result(future, 20.seconds)
      }
      assert(thrown.getMessage.contains(traceID))
    }

    "should bubble exception thrown by the tcp stage" in {
      val client = Sonic.Client(new InetSocketAddress("0.0.0.0", 10010))(system, materializer)

      val future: Future[Vector[SonicMessage]] = client.run(syntheticQuery)

      val (cancellable, future2) = client.stream(syntheticQuery).toMat(Sink.ignore)(Keep.both).run()

      intercept[StreamException] {
        Await.result(future, 20.seconds)
      }.getCause.getMessage.contains("Connect") shouldBe true

      intercept[StreamException] {
        Await.result(future2, 20.seconds)
      }.getCause.getMessage.contains("Connect") shouldBe true
    }
  }
}
