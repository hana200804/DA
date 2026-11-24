package vn.signa.recognition.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

/** Ảnh nhận diện chỉ nằm trong bộ nhớ: Tomcat không ghi multipart ra file tạm (NFR-09). */
class UploadConfigTest {

  @Test
  void uploadsUpToTheLimitStayInMemory() throws IOException {
    PropertySource<?> yml =
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"))
            .getFirst();

    DataSize threshold = size(yml, "spring.servlet.multipart.file-size-threshold");
    DataSize maxFile = size(yml, "spring.servlet.multipart.max-file-size");
    DataSize maxRequest = size(yml, "spring.servlet.multipart.max-request-size");

    // Tomcat chỉ ghi phần multipart ra đĩa khi nó lớn hơn ngưỡng.
    assertThat(threshold.toBytes()).isGreaterThanOrEqualTo(maxFile.toBytes());
    assertThat(threshold.toBytes()).isGreaterThanOrEqualTo(maxRequest.toBytes());
    assertThat(maxFile.toBytes()).isGreaterThanOrEqualTo(ImageProcessor.MAX_BYTES);
  }

  private static DataSize size(PropertySource<?> source, String key) {
    Object value = source.getProperty(key);
    assertThat(value).as(key).isNotNull();
    return DataSize.parse(value.toString());
  }
}
