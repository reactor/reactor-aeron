package reactor.aeron.demo.raw;

import io.aeron.Aeron;
import io.aeron.ChannelUriStringBuilder;
import io.aeron.CommonContext;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.Subscription;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.aeron.WorkerFlightRecorder;
import reactor.aeron.WorkerMBean;
import reactor.aeron.demo.raw.RawAeronResources.MsgPublication;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class RawAeronClientThroughput {

  private static final Logger logger = LoggerFactory.getLogger(RawAeronClientThroughput.class);

  /**
   * Main runner.
   *
   * @param args program arguments.
   */
  public static void main(String[] args) throws Exception {
    Aeron aeron = RawAeronResources.start();
    new Client(aeron).start();
  }

  private static class Client {

    private static final String address = "localhost";
    private static final int port = 13000;
    private static final int controlPort = 13001;
    private static final int STREAM_ID = 0xcafe0000;
    private static final ChannelUriStringBuilder outboundChannelBuilder =
        new ChannelUriStringBuilder()
            .endpoint(address + ':' + port)
            .reliable(Boolean.TRUE)
            .media("udp");
    private static final ChannelUriStringBuilder inboundChannelBuilder =
        new ChannelUriStringBuilder()
            .controlEndpoint(address + ':' + controlPort)
            .controlMode(CommonContext.MDC_CONTROL_MODE_DYNAMIC)
            .reliable(Boolean.TRUE)
            .media("udp");

    private final Aeron aeron;

    private final Scheduler scheduler = Schedulers.newSingle("client@" + this);
    private final IdleStrategy idleStrategy = new BackoffIdleStrategy(1, 1, 1, 100);
    // private final RateReporter reporter = new RateReporter(Duration.ofSeconds(1));
    private final WorkerFlightRecorder flightRecorder;
    private final int writeLimit = 8;

    private final DirectBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(1024));

    Client(Aeron aeron) throws Exception {
      this.aeron = aeron;

      this.flightRecorder = new WorkerFlightRecorder();
      MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
      ObjectName objectName = new ObjectName("reactor.aeron:name=" + "server@" + this);
      StandardMBean standardMBean = new StandardMBean(flightRecorder, WorkerMBean.class);
      mbeanServer.registerMBean(standardMBean, objectName);
    }

    public void start() {
      scheduler.schedule(
          () -> {
            String outboundChannel = outboundChannelBuilder.build();

            Publication publication = aeron.addExclusivePublication(outboundChannel, STREAM_ID);

            int sessionId = publication.sessionId();

            String inboundChannel = inboundChannelBuilder.sessionId(sessionId).build();

            Subscription subscription =
                aeron.addSubscription(
                    inboundChannel,
                    STREAM_ID,
                    this::onClientImageAvailable,
                    this::onClientImageUnavailable);

            MsgPublication msgPublication = new MsgPublication(publication, writeLimit);

            scheduler.schedule(
                () -> {
                  flightRecorder.begin();

                  while (true) {
                    flightRecorder.countTick();

                    int i = processOutbound(msgPublication);
                    flightRecorder.countOutbound(i);

                    int j = processInbound();
                    flightRecorder.countInbound(j);

                    int workCount = i + j;
                    if (workCount < 1) {
                      flightRecorder.countIdle();
                    } else {
                      flightRecorder.countWork(workCount);
                    }

                    // Reporting
                    flightRecorder.tryReport();

                    idleStrategy.idle(workCount);
                  }
                });
          });
    }

    private void onClientImageAvailable(Image image) {
      logger.debug(
          "onClientImageAvailable: {} {}",
          Integer.toHexString(image.sessionId()),
          image.sourceIdentity());
    }

    private void onClientImageUnavailable(Image image) {
      logger.debug(
          "onClientImageUnavailable: {} {}",
          Integer.toHexString(image.sessionId()),
          image.sourceIdentity());
    }

    private int processInbound() {
      return 0;
    }

    private int processOutbound(MsgPublication msgPublication) {
      return msgPublication.proceed(buffer);
    }
  }
}
