package vn.signa.shared.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Cấu hình riêng của Signa, đọc từ biến môi trường (docs/03 §9). */
@Validated
@ConfigurationProperties(prefix = "signa")
public record SignaProperties(Minio minio, Recognition recognition, String labelsPath) {

  public record Minio(
      @NotBlank String endpoint,
      @NotBlank String publicEndpoint,
      String accessKey,
      String secretKey,
      @NotBlank String region) {}

  public record Recognition(@NotBlank String url) {}
}
