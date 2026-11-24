package vn.signa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Phiên do filter riêng của identity xác thực; không dùng UserDetailsService mặc định, vốn ghi một
// mật khẩu sinh ngẫu nhiên ra log.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class SignaApplication {

  public static void main(String[] args) {
    SpringApplication.run(SignaApplication.class, args);
  }
}
