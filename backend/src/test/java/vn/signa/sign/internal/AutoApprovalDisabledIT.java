package vn.signa.sign.internal;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {"signa.clustering.auto-approve=false", "spring.data.redis.database=10"})
class AutoApprovalDisabledIT extends AutoApprovalWorkerSupport {

  @Test
  void utB10WorkerKeepsSignAndMembersPending() {
    verifyWorkerDecision(false);
  }
}
