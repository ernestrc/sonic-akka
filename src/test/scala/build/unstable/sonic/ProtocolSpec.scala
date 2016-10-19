package build.unstable.sonic

import akka.http.scaladsl.testkit.ScalatestRouteTest
import build.unstable.sonic.model.{Query, SonicMessage}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import spray.json.JsString

class ProtocolSpec extends WordSpec with Matchers with ScalatestRouteTest
  with BeforeAndAfterAll with JsonProtocol {

  "protocol" should {
    "correctly serialize a query with auth config null" in {
      val msg = SonicMessage.fromJson("""{"e":"Q","p":{"config":"whatever","auth":null},"v":"1234"}""")
      msg shouldBe an[Query]

      val query = msg.asInstanceOf[Query]
      query.auth shouldBe None
      query.traceId shouldBe None
      query.query shouldBe "1234"
      query.config shouldBe JsString("whatever")
    }
  }
}
