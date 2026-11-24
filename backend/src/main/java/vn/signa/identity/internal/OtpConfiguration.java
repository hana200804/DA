package vn.signa.identity.internal;

import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
class OtpConfiguration {

  @Bean
  OtpCodes otpCodes(OtpProperties properties, Environment environment) {
    String secret = properties.secret();
    if (secret == null || secret.isBlank()) {
      if (!environment.matchesProfiles("test")) {
        throw new IllegalStateException("Phải đặt SIGNA_OTP_SECRET");
      }
      secret = SessionTokens.newToken();
    }
    return new OtpCodes(secret, new SecureRandom());
  }

  @Bean(defaultCandidate = false)
  JavaMailSender otpMailSender(OtpProperties properties) {
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(properties.smtpHost());
    sender.setPort(properties.smtpPort());
    sender.setDefaultEncoding("UTF-8");
    var smtp = sender.getJavaMailProperties();
    smtp.setProperty("mail.smtp.connectiontimeout", "3000");
    smtp.setProperty("mail.smtp.timeout", "3000");
    smtp.setProperty("mail.smtp.writetimeout", "3000");
    return sender;
  }

  @Bean(defaultCandidate = false)
  ThreadPoolTaskExecutor passwordOtpExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(64);
    executor.setThreadNamePrefix("password-otp-");
    return executor;
  }
}
