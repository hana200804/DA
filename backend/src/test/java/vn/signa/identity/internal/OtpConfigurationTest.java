package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class OtpConfigurationTest {

  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(OtpConfiguration.class, PropertiesConfiguration.class);

  @ParameterizedTest
  @ValueSource(strings = {"local", "migrate"})
  void contextFailsWithoutSecretOutsideTest(String profile) {
    context
        .withPropertyValues("spring.profiles.active=" + profile)
        .run(application ->
            assertThat(application.getStartupFailure())
                .hasStackTraceContaining("Phải đặt SIGNA_OTP_SECRET"));
  }

  @Test
  void testProfileGeneratesSecretWhenMissing() {
    context
        .withPropertyValues("spring.profiles.default=test")
        .run(application -> {
          assertThat(application).hasSingleBean(OtpCodes.class);
          assertThat(application.getBean(OtpCodes.class).generate()).matches("[0-9]{6}");
        });
  }

  @Test
  void configuresSmtpFromIdentityProperties() {
    context
        .withPropertyValues(
            "signa.identity.otp.secret=test-key",
            "signa.identity.otp.smtp-host=mailpit",
            "signa.identity.otp.smtp-port=2025")
        .run(application -> {
          assertThat(application).hasSingleBean(OtpCodes.class);
          JavaMailSenderImpl sender = (JavaMailSenderImpl) application.getBean("otpMailSender");
          assertThat(sender.getHost()).isEqualTo("mailpit");
          assertThat(sender.getPort()).isEqualTo(2025);
          assertThat(sender.getDefaultEncoding()).isEqualTo("UTF-8");
        });
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableConfigurationProperties(OtpProperties.class)
  static class PropertiesConfiguration {}
}
