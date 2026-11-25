package vn.signa.sign.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;
import vn.signa.sign.internal.SubmissionRepository.Received;

@Component
@Profile("!migrate")
@ConditionalOnProperty(name = "signa.clustering.worker.enabled", havingValue = "true")
class ClusteringWorker implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ClusteringWorker.class);
  private static final Duration RECEIVED_AGE = Duration.ofMinutes(1);
  private static final int SWEEP_BATCH_SIZE = 250;
  private static final long MIN_BACKOFF_MS = 250;
  private static final long MAX_BACKOFF_MS = 15_000;

  private final SubmissionStream stream;
  private final ClusteringProcessor processor;
  private final SubmissionRepository submissions;
  private final Clock clock;
  private final ClusteringProperties.Worker config;
  private final ClusteringMetrics metrics;

  private volatile boolean running;
  private Thread thread;

  ClusteringWorker(
      SubmissionStream stream,
      ClusteringProcessor processor,
      SubmissionRepository submissions,
      Clock clock,
      ClusteringProperties properties,
      ClusteringMetrics metrics) {
    this.stream = stream;
    this.processor = processor;
    this.submissions = submissions;
    this.clock = clock;
    this.config = properties.worker();
    this.metrics = metrics;
  }

  @Override
  public synchronized void start() {
    if (running || (thread != null && thread.isAlive())) return;
    running = true;
    thread = Thread.ofPlatform().daemon().name("signa-clustering").start(this::run);
  }

  @Override
  public synchronized void stop() {
    running = false;
    if (thread == null) return;
    thread.interrupt();
    try {
      thread.join(Duration.ofSeconds(5));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void run() {
    long backoff = MIN_BACKOFF_MS;
    try {
      while (running) {
        try {
          stream.createGroup();
          recoverPending();
          Instant nextSweep = clock.instant();
          while (running) {
            if (!clock.instant().isBefore(nextSweep)) {
              sweep();
              nextSweep = clock.instant().plus(config.sweepInterval());
            }
            process(stream.read(config.consumer()));
            backoff = MIN_BACKOFF_MS;
          }
        } catch (RuntimeException e) {
          if (!running) break;
          log.warn("Worker gom cụm sẽ thử lại sau {} ms", backoff, e);
          Thread.sleep(backoff);
          backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      running = false;
    }
  }

  private void recoverPending() {
    while (running) {
      var pending = stream.claim(config.consumer());
      if (pending.isEmpty()) return;
      process(pending);
    }
  }

  private void sweep() {
    Instant cutoff = clock.instant().minus(RECEIVED_AGE);
    Received cursor = new Received("", Instant.parse("1000-01-01T00:00:00Z"));
    List<Received> batch;
    do {
      batch = submissions.receivedBefore(cutoff, cursor, SWEEP_BATCH_SIZE);
      for (Received entry : batch) {
        if (!running) return;
        stream.add(entry.id());
        cursor = entry;
      }
    } while (batch.size() == SWEEP_BATCH_SIZE);
  }

  private void process(List<MapRecord<String, String, String>> entries) {
    for (var entry : entries) {
      if (!running) return;
      String id = entry.getValue().get("id");
      if (id == null) {
        log.warn("Bỏ mục Redis {} thiếu id đề xuất", entry.getId());
      } else {
        metrics.record(() -> processor.process(id));
      }
      stream.complete(entry.getId());
    }
  }
}
