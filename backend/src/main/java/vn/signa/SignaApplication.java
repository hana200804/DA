package vn.signa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SignaApplication {

  public static void main(String[] args) {
    SpringApplication.run(SignaApplication.class, args);
  }
}
