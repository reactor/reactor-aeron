package reactor.ipc.aeron.demo;

import io.aeron.driver.AeronResources;
import reactor.core.publisher.Mono;
import reactor.ipc.aeron.server.AeronServer;

public class ServerDemo {

  /**
   * Main runner.
   *
   * @param args program arguments.
   */
  public static void main(String[] args) throws Exception {
    try (AeronResources aeronResources = new AeronResources("test")) {

      AeronServer.create("server", aeronResources)
          .options(options -> options.serverChannel("aeron:udp?endpoint=localhost:13000"))
          .handle(
              (inbound, outbound) -> {
                inbound.receive().asString().log("receive").subscribe();
                return Mono.never();
              })
          .bind()
          .block();

      Thread.currentThread().join();
    }
  }
}
