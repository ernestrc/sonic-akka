# Sonic Akka [![Build Status](https://travis-ci.org/xarxa6/sonic-akka.svg)](https://travis-ci.org/xarxa6/sonic-akka)

Akka Streams API for the Sonic protocol

# Installation
Add to your `plugins.sbt`:
```
addSbtPlugin("me.lessis" % "bintray-sbt" % "0.3.0")
```

Add to your `build.sbt` or `Build.scala`:
```
   resolvers += Resolver.bintrayRepo("ernestrc", "maven"),

   libraryDependencies ++= {
     Seq("build.unstable" %% "sonic-core" % "0.6.4")
   },

```

# Usage
Create a Sonic endpoint:
```scala
object SonicServer {
  val ADDR = new InetSocketAddress("127.0.0.1", 10001)
}

class SonicServer(implicit system: ActorSystem) {
  // obtain a reference to the IO manager actor for Tcp
  val tcpIoService: ActorRef = IO(Tcp)

  // create a new controller which will handle NewCommand
  val controller: ActorRef = actor(new Act {
    become {
      // tcp handler expects a reply with token: String or Failure(e)
      case NewCommand(Authenticate(user, key, _), addr) ⇒ sender() ! "aValidToken"
      // tcp handler expects reply with the Props of an ActorPublisher[SonicMessage] or Failure(e)
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
```

Consuming from a Sonic endpoint:
```scala
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
}
```
For Examples in Java check the [./examples/src/main](./examples/src/main) directory.

# Contribute
If you would like to contribute to the project, please fork the project, include your changes and submit a pull request back to the main repository.

# License
MIT License 
