package vn.signa.recognition.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Cấu hình module recognition. {@code url} đã có trong {@code application.yml} từ lát 0; các trường
 * còn lại dùng giá trị mặc định theo docs/backend/thiet-ke-trien-khai.md §5.1, §5.3.
 */
@Validated
@ConfigurationProperties("signa.recognition")
public record RecognitionProperties(
    @NotBlank String url,
    @NotNull @DefaultValue("1s") Duration connectTimeout,
    @NotNull @DefaultValue("5s") Duration readTimeout,
    @NotNull @DefaultValue("60s") Duration modelsCacheTtl,
    @Positive @DefaultValue("30") int guestPerMinute,
    @Positive @DefaultValue("10") int userPerMinute) {}
