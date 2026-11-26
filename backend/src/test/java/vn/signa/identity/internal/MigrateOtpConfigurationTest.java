package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"signa.identity.otp.secret=", "spring.flyway.enabled=false"})
@ActiveProfiles("migrate")
class MigrateOtpConfigurationTest {

  @Autowired ApplicationContext context;

  // Giữ context mở để kiểm tra sau khi khởi động, vì runner thật gọi System.exit.
  @MockitoBean(name = "migrateAndExit", enforceOverride = true)
  ApplicationRunner migrateAndExit;

  @Test
  void migrateContextStartsWithoutOtpSecret() {
    assertThat(context.getEnvironment().getProperty("spring.main.web-application-type"))
        .isEqualTo("none");
    assertThat(context.getBeanNamesForType(OtpCodes.class)).isEmpty();
    assertThat(context.getBeanNamesForType(RedisOtpStore.class)).isEmpty();
    assertThat(context.getBeanNamesForType(PasswordResetService.class)).isEmpty();
    assertThat(context.containsBean("passwordResetController")).isFalse();
    assertThat(context.containsBean("otpMailSender")).isFalse();
    assertThat(context.containsBean("passwordOtpExecutor")).isFalse();
  }
}
