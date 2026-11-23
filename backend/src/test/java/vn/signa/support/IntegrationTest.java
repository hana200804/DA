package vn.signa.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Lớp cha của test tích hợp. Mọi lớp con dùng chung cấu hình nên Spring dùng lại một context, và
 * Flyway chỉ migrate một lần.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    Containers.register(registry);
  }
}
