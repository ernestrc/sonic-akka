package build.unstable.sonic.cluster

import java.net.InetSocketAddress

import akka.actor.ActorSystem

/**
  * Handled by system controllers and used to spread sonic endpoint addresses
  */
case class RequestEndpoint(address: InetSocketAddress)
