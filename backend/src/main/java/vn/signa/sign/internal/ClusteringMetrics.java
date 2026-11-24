package vn.signa.sign.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!migrate")
class ClusteringMetrics {

  private final Timer process;

  ClusteringMetrics(
      SubmissionStream stream,
      SubmissionRepository submissions,
      Clock clock,
      MeterRegistry registry) {
    Gauge.builder("signa.clustering.stream.length", stream, SubmissionStream::length)
        .register(registry);
    Gauge.builder("signa.clustering.pending", stream, SubmissionStream::pending).register(registry);
    Gauge.builder(
            "signa.clustering.received.count", submissions, SubmissionRepository::countReceived)
        .register(registry);
    Gauge.builder(
            "signa.clustering.received.oldest.seconds",
            submissions,
            repo ->
                repo.oldestReceived()
                    .map(at ->
                        Math.max(0, Duration.between(at, clock.instant()).toMillis() / 1000.0))
                    .orElse(0.0))
        .register(registry);
    process = registry.timer("signa.clustering.process.duration");
  }

  void record(Runnable operation) {
    process.record(operation);
  }
}
