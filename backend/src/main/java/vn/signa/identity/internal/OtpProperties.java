package vn.signa.identity.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("signa.identity.otp")
record OtpProperties(
    String secret,
    @DefaultValue("localhost") String smtpHost,
    @DefaultValue("1025") int smtpPort,
    @DefaultValue("no-reply@signa.local") String smtpFrom) {

  @Override
  public String toString() {
    return "OtpProperties[secret=***, smtpHost=" + smtpHost + ", smtpPort=" + smtpPort + "]";
  }
}
