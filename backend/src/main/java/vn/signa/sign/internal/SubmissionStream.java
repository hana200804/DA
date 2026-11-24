package vn.signa.sign.internal;

import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class SubmissionStream {

  static final String KEY = "stream:submissions";

  private final StringRedisTemplate redis;

  SubmissionStream(StringRedisTemplate redis) {
    this.redis = redis;
  }

  void add(String submissionId) {
    redis.opsForStream().add(KEY, Map.of("id", submissionId));
  }
}
