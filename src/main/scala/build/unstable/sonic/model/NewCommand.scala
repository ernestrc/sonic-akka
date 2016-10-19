package build.unstable.sonic.model

import java.net.InetAddress

case class NewCommand(command: SonicCommand, clientAddress: Option[InetAddress])
