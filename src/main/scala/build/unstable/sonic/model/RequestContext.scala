package build.unstable.sonic.model

import java.net.InetAddress

case class RequestContext(traceId: String, user: Option[ApiUser], clientAddress: Option[InetAddress] = None)
