package build.unstable.sonic.model

import akka.actor._

/**
  * Base abstract implementation of a source of data in Sonicd. New implementations can be added to Sonicd's classpath.
  *
  * Concrete implementations need to implement the ``publisher`` method, which needs to be an [[akka.actor.Props]]
  * of an [[Actor]] that implements [[akka.stream.actor.ActorPublisher]] of [[SonicMessage]]
  */
abstract class DataSource(query: Query, actorContext: ActorContext, context: RequestContext) {

  def publisher: Props

}
