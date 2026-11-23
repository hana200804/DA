package vn.signa.shared.storage;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Tạo bucket khi khởi động, trước khi server nhận request. Thiếu MinIO thì ứng dụng không lên. Bỏ
 * qua ở chế độ {@code migrate}, vì chế độ đó chỉ cần MySQL.
 */
@Component
@Profile("!migrate")
class MinioBucketInitializer implements InitializingBean {

  private final MinioObjectStorage storage;

  MinioBucketInitializer(MinioObjectStorage storage) {
    this.storage = storage;
  }

  @Override
  public void afterPropertiesSet() {
    storage.ensureBuckets();
  }
}
