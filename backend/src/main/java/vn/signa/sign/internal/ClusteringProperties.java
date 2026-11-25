package vn.signa.sign.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("signa.clustering")
record ClusteringProperties(
    @DefaultValue("false") boolean autoApprove, @DefaultValue Worker worker) {

  record Worker(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("signa-backend") String consumer,
      @DefaultValue("1m") Duration sweepInterval) {
    Worker {
      if (consumer.isBlank()) throw new IllegalArgumentException("Tên consumer không được rỗng");
      if (sweepInterval.isZero() || sweepInterval.isNegative()) {
        throw new IllegalArgumentException("Chu kỳ quét phải dương");
      }
    }
  }
}
