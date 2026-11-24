package vn.signa.sign.internal;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

// Redis DB riêng để worker của các IT khác không đọc luồng của ca này.
@TestPropertySource(
    properties = {"signa.clustering.auto-approve=true", "spring.data.redis.database=9"})
class AutoApprovalEnabledIT extends AutoApprovalWorkerSupport {

  @Test
  void utB09WorkerApprovesAndUpdatesMembersWithSystemHistory() {
    verifyWorkerDecision(true);
  }
}
