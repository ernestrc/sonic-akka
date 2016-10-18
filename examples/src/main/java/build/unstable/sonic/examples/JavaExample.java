package build.unstable.sonic.examples;

import akka.Done;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import build.unstable.sonic.javadsl.Query;
import build.unstable.sonic.javadsl.SonicClient;
import build.unstable.sonic.model.SonicMessage;
import spray.json.JsString;
import spray.json.JsValue;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Makes use of `sonicd-core` artifact which provides a streaming and futures API
 */
class JavaExample {
    public static void main(String[] args) {
        ActorSystem system = ActorSystem.create("SonicJavaExample");
        Materializer materializer = ActorMaterializer.create(system);

        // sonic server address
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 10001);

        // source configuration

        HashMap<String, JsValue> config = new HashMap();
        config.put("class", new JsString("SyntheticSource"));

        // generate a new traceId
        Optional<String> traceId = Optional.of(UUID.randomUUID().toString());

        // instantiate client, which will allocate resources to query sonic endpoint
        SonicClient client = new SonicClient(addr, system, materializer);


        // use stream api

        Query query1 = new Query("100", Optional.empty(), traceId, config);

        Source<SonicMessage, Cancellable> source1 = client.stream(query1);
        Sink<SonicMessage, CompletionStage<Done>> sink1 = Sink.ignore();
        Cancellable cancellable = Source.fromGraph(source1).to(sink1).run(materializer);

        cancellable.cancel();

        assert (cancellable.isCancelled());


        // use Future api

        Query query = new Query("10", Optional.empty(), traceId, config);

        Future<Collection<SonicMessage>> res = client.run(query);

        Collection<SonicMessage> done;
        try {
            done = res.get(10, TimeUnit.SECONDS);
            assert (done.size() == 113); //1 started + 1 metadata + 100 QueryProgress + 10 OutputChunk + 1 DoneWithQueryExecution
        } catch (Exception e) {
            system.log().error(e, "somethign went wrong with " + traceId);
        } finally {
            system.terminate();
        }
    }
}
