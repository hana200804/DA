package vn.signa.support;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * MySQL, Redis, MinIO dùng chung cho mọi test {@code *IT}. Khởi động một lần cho cả JVM, Ryuk dọn
 * khi JVM thoát. Phiên bản ảnh khớp {@code infra/compose.yaml}.
 */
public final class Containers {

  public static final String APP_USER = "signa_app";
  public static final String APP_PASSWORD = "app-it-secret";
  public static final String MIGRATOR_USER = "signa_migrator";
  public static final String MIGRATOR_PASSWORD = "migrator-it-secret";

  /** Bản cộng đồng của MinIO: ảnh {@code minio/minio} chính thức không còn công khai. */
  static final DockerImageName MINIO_IMAGE =
      DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
          .asCompatibleSubstituteFor("minio/minio");

  static final int REDIS_PORT = 6379;

  public static final MySQLContainer MYSQL = newMysql();

  public static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4.12-alpine"))
          .withExposedPorts(REDIS_PORT);

  public static final MinIOContainer MINIO = new MinIOContainer(MINIO_IMAGE);

  static {
    Startables.deepStart(MYSQL, REDIS, MINIO).join();
  }

  private Containers() {}

  /**
   * Trỏ ứng dụng vào container. Ứng dụng kết nối bằng {@code signa_app}, Flyway bằng {@code
   * signa_migrator}, như khi chạy thật. Không dùng {@code @ServiceConnection} cho MySQL vì nó sẽ
   * dùng tài khoản test của container.
   */
  public static void register(DynamicPropertyRegistry registry) {
    register(registry, MYSQL);
  }

  /** Trỏ MySQL vào một container riêng, vẫn dùng chung Redis và MinIO. */
  public static void register(DynamicPropertyRegistry registry, MySQLContainer mysql) {
    registry.add("spring.datasource.url", mysql::getJdbcUrl);
    registry.add("spring.datasource.username", () -> APP_USER);
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.user", () -> MIGRATOR_USER);
    registry.add("spring.flyway.password", () -> MIGRATOR_PASSWORD);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    registry.add("signa.minio.endpoint", MINIO::getS3URL);
    registry.add("signa.minio.public-endpoint", MINIO::getS3URL);
    registry.add("signa.minio.access-key", MINIO::getUserName);
    registry.add("signa.minio.secret-key", MINIO::getPassword);
  }

  /** Chạy cùng script tạo tài khoản với Compose (docs/backend/thiet-ke-trien-khai.md §5.3). */
  public static MySQLContainer newMysql() {
    return new MySQLContainer(DockerImageName.parse("mysql:8.4.13"))
        .withDatabaseName("signa")
        .withPassword("root-it-secret")
        .withUrlParam("connectionTimeZone", "UTC")
        .withUrlParam("forceConnectionTimeZoneToSession", "true")
        .withEnv("SIGNA_MIGRATOR_PASSWORD", MIGRATOR_PASSWORD)
        .withEnv("SIGNA_APP_PASSWORD", APP_PASSWORD)
        .withCopyFileToContainer(
            MountableFile.forHostPath(usersScript(), 0755),
            "/docker-entrypoint-initdb.d/01-users.sh");
  }

  /** Gradle chạy test trong thư mục {@code backend/}. */
  private static Path usersScript() {
    Path script =
        Path.of("..", "infra", "mysql", "init", "01-users.sh").toAbsolutePath().normalize();
    if (!Files.isRegularFile(script)) {
      throw new IllegalStateException("Không tìm thấy " + script);
    }
    return script;
  }
}
