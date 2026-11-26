package vn.signa.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Một {@link Clock} UTC dùng chung để test thay được thời gian. */
@Configuration
public class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
