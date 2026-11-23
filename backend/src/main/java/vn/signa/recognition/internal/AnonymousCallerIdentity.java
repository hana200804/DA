package vn.signa.recognition.internal;

import java.util.Optional;
import org.springframework.stereotype.Component;

/** Bản tạm khi chưa có Spring Security (lát 1): mọi người gọi đều là khách. */
@Component
class AnonymousCallerIdentity implements CallerIdentity {

  @Override
  public Optional<String> userId() {
    return Optional.empty();
  }
}
