package reactor.aeron;

import static java.lang.Boolean.TRUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeron.ChannelUriStringBuilder;
import io.aeron.driver.Configuration;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.aeron.client.AeronClient;
import reactor.aeron.server.AeronServer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.ReplayProcessor;
import reactor.test.StepVerifier;

class AeronClientTest extends BaseAeronTest {

  private ChannelUriStringBuilder serverChannel;
  private ChannelUriStringBuilder clientChannel;
  private AeronResources aeronResources;

  @BeforeEach
  void beforeEach() {
    serverChannel =
        new ChannelUriStringBuilder()
            .reliable(TRUE)
            .media("udp")
            .endpoint("localhost:" + SocketUtils.findAvailableUdpPort(13000, 14000));
    clientChannel =
        new ChannelUriStringBuilder()
            .reliable(TRUE)
            .media("udp")
            .endpoint("localhost:" + SocketUtils.findAvailableUdpPort(14000, 15000));
    aeronResources = AeronResources.start(AeronResourcesConfig.builder().build());
  }

  @AfterEach
  void afterEach() {
    if (aeronResources != null) {
      aeronResources.dispose();
      aeronResources.onDispose().block(TIMEOUT);
    }
  }

  @Test
  public void testClientCouldNotConnectToServer() {
    assertThrows(RuntimeException.class, this::createConnection);
  }

  @Test
  public void testClientReceivesDataFromServer() {
    createServer(
        connection ->
            connection
                .outbound()
                .send(ByteBufferFlux.from("hello1", "2", "3").log("server"))
                .then(connection.onDispose()));

    Connection connection = createConnection();
    StepVerifier.create(connection.inbound().receive().asString().log("client"))
        .expectNext("hello1", "2", "3")
        .expectNoEvent(Duration.ofMillis(10))
        .thenCancel()
        .verify();
  }

  @Test
  public void testClientReceivesLongDataFromServer() {
    char[] chars = new char[Configuration.MTU_LENGTH * 2];
    Arrays.fill(chars, 'a');
    String str = new String(chars);

    createServer(
        connection ->
            connection
                .outbound()
                .send(ByteBufferFlux.from(str, str, str).log("server"))
                .then(connection.onDispose()));

    Connection connection = createConnection();

    StepVerifier.create(connection.inbound().receive().asString().log("client"))
        .expectNext(str, str, str)
        .expectNoEvent(Duration.ofMillis(10))
        .thenCancel()
        .verify();
  }

  @Test
  public void testTwoClientsReceiveDataFromServer() {
    createServer(
        connection ->
            connection
                .outbound()
                .send(ByteBufferFlux.from("1", "2", "3").log("server"))
                .then(connection.onDispose()));

    Connection connection1 = createConnection();
    Connection connection2 = createConnection();

    StepVerifier.create(connection1.inbound().receive().asString().log("client-1"))
        .expectNext("1", "2", "3")
        .expectNoEvent(Duration.ofMillis(100))
        .thenCancel()
        .verify();

    StepVerifier.create(connection2.inbound().receive().asString().log("client-2"))
        .expectNext("1", "2", "3")
        .expectNoEvent(Duration.ofMillis(100))
        .thenCancel()
        .verify();
  }

  @Test
  public void testClientsReceiveDataFromServer200000() {
    int count = 200_000;
    Flux<ByteBuffer> payloads =
        Flux.range(0, count).map(String::valueOf).map(AeronUtils::stringToByteBuffer);

    createServer(
        connection ->
            connection.outbound().send(ByteBufferFlux.from(payloads)).then(connection.onDispose()));

    Connection connection1 = createConnection();

    StepVerifier.create(connection1.inbound().receive().asString())
        .expectNextCount(count)
        .expectNoEvent(Duration.ofMillis(100))
        .thenCancel()
        .verify();
  }

  @Test
  public void testRequestResponse200000() {
    int count = 200_000;
    createServer(
        connection ->
            connection
                .outbound()
                .send(connection.inbound().receive())
                .then(connection.onDispose()));

    Connection connection1 = createConnection();

    connection1
        .outbound()
        .send(Flux.range(0, count).map(String::valueOf).map(AeronUtils::stringToByteBuffer))
        .then()
        .subscribe();

    StepVerifier.create(connection1.inbound().receive().asString())
        .expectNextCount(count)
        .expectNoEvent(Duration.ofMillis(100))
        .thenCancel()
        .verify();
  }

  @Test
  public void testRequestResponse200000DoesntWork() {
    int count = 200_000;
    createServer(
        connection ->
            connection
                .inbound()
                .receive()
                .flatMap(byteBuffer -> connection.outbound().send(Mono.just(byteBuffer)).then())
                .then(connection.onDispose()));

    Connection connection1 = createConnection();

    Flux.range(0, count)
        .flatMap(i -> connection1.outbound().send(ByteBufferFlux.from("client_send:" + i)).then())
        .then()
        .subscribe();

    StepVerifier.create(connection1.inbound().receive().asString())
        .expectNextCount(count)
        .expectNoEvent(Duration.ofMillis(100))
        .thenCancel()
        .verify(Duration.ofSeconds(20));
  }

  @Test
  public void testTwoClientsRequestResponse200000() {
    int count = 200_000;
    createServer(
        connection ->
            connection
                .inbound()
                .receive()
                .flatMap(byteBuffer -> connection.outbound().send(Mono.just(byteBuffer)).then())
                .then(connection.onDispose()));

    Connection connection1 = createConnection();
    Flux.range(0, count)
        .flatMap(i -> connection1.outbound().send(ByteBufferFlux.from("client-1 send:" + i)).then())
        .then()
        .subscribe();

    Connection connection2 = createConnection();
    Flux.range(0, count)
        .flatMap(i -> connection2.outbound().send(ByteBufferFlux.from("client-2 send:" + i)).then())
        .then()
        .subscribe();

    StepVerifier.create(
            Flux.merge(
                connection1
                    .inbound()
                    .receive()
                    .asString()
                    .take(count)
                    .filter(response -> !response.startsWith("client-1 ")),
                connection2
                    .inbound()
                    .receive()
                    .asString()
                    .take(count)
                    .filter(response -> !response.startsWith("client-2 "))))
        .expectComplete()
        .verify(Duration.ofSeconds(20));
  }

  @Test
  public void testClientWith2HandlersReceiveData() {
    createServer(
        connection ->
            connection
                .outbound()
                .send(ByteBufferFlux.from("1", "2", "3").log("server"))
                .then(connection.onDispose()));

    ReplayProcessor<String> processor1 = ReplayProcessor.create();
    ReplayProcessor<String> processor2 = ReplayProcessor.create();

    createConnection(
        connection -> {
          connection.inbound().receive().asString().log("client-1").subscribe(processor1);
          return connection.onDispose();
        });

    createConnection(
        connection -> {
          connection.inbound().receive().asString().log("client-2").subscribe(processor2);
          return connection.onDispose();
        });

    StepVerifier.create(processor1)
        .expectNext("1", "2", "3")
        .expectNoEvent(Duration.ofMillis(100))
        .thenCancel()
        .verify();

    StepVerifier.create(processor2)
        .expectNext("1", "2", "3")
        .expectNoEvent(Duration.ofMillis(100))
        .thenCancel()
        .verify();
  }

  @Test
  public void testClientClosesSessionAndServerHandleUnavailableImage() throws Exception {
    // close current redundant aeron resources
    this.aeronResources.nextEventLoop().execute(MonoSink::success).subscribe(); // workaround
    this.aeronResources.dispose();
    this.aeronResources.onDispose().block(Duration.ofSeconds(5));

    Duration imageLivenessTimeout = Duration.ofSeconds(1);
    this.aeronResources =
        AeronResources.start(
            AeronResourcesConfig.builder().imageLivenessTimeout(imageLivenessTimeout).build());

    OnDisposable server =
        createServer(
            connection ->
                connection
                    .outbound()
                    .send(
                        ByteBufferFlux.from("hello1", "2", "3")
                            .delayElements(Duration.ofSeconds(1))
                            .log("server1"))
                    .then(connection.onDispose()));

    ReplayProcessor<String> processor = ReplayProcessor.create();

    Connection connection =
        createConnection(
            options -> {
              options.clientChannel(clientChannel);
              options.serverChannel(serverChannel);
            });

    CountDownLatch latch = new CountDownLatch(1);
    connection.onDispose().doOnSuccess(aVoid -> latch.countDown()).subscribe();

    connection.inbound().receive().asString().log("client").subscribe(processor);

    processor.take(1).blockLast(Duration.ofSeconds(4));

    server.dispose();

    latch.await(imageLivenessTimeout.toMillis(), TimeUnit.MILLISECONDS);

    assertEquals(0, latch.getCount());
  
  }

  @Test
  public void testConcurrentSendingStreams() {
    int overallCount = 200;
    int streams = 2;
    int requestPerStream = overallCount / streams;

    ReplayProcessor<String> clientRequests = ReplayProcessor.create();

    createServer(
        connection ->
            connection
                .inbound()
                .receive()
                .asString()
                .doOnNext(clientRequests::onNext)
                // .log("server receive ")
                .then(connection.onDispose()));

    createConnection(
        connection -> {
          for (int i = 0; i < streams; i++) {
            int start = i * requestPerStream;
            int count = start + requestPerStream;
            connection
                .outbound()
                .send(
                    Flux.range(start, count)
                        .map(n -> AeronUtils.stringToByteBuffer(String.valueOf(n))))
                .then()
                .subscribe(null, Throwable::printStackTrace);
          }
          return connection.onDispose();
        });

    List<Integer> requests =
        clientRequests
            .take(requestPerStream * streams)
            .map(Integer::parseInt)
            .collectList()
            .block(Duration.ofSeconds(10));

    ArrayList<Integer> sortedRequests = new ArrayList<>(requests);
    Collections.sort(sortedRequests);
    assertNotEquals(requests, sortedRequests);
  }

  private Connection createConnection() {
    return createConnection(
        options -> {
          options.clientChannel(clientChannel);
          options.serverChannel(serverChannel);
        });
  }

  private Connection createConnection(Consumer<AeronOptions.Builder> options) {
    return AeronClient.create(aeronResources).options(options).connect().block(TIMEOUT);
  }

  private Connection createConnection(
      Function<? super Connection, ? extends Publisher<Void>> handler) {
    return AeronClient.create(aeronResources)
        .options(
            options -> {
              options.clientChannel(clientChannel);
              options.serverChannel(serverChannel);
            })
        .handle(handler)
        .connect()
        .block(TIMEOUT);
  }

  private OnDisposable createServer(
      Function<? super Connection, ? extends Publisher<Void>> handler) {
    return AeronServer.create(aeronResources)
        .options(options -> options.serverChannel(serverChannel))
        .handle(handler)
        .bind()
        .block(TIMEOUT);
  }
}
