package vn.signa.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/** Backend chạy lâu dài không migrate và không đọc mật khẩu {@code signa_migrator}. */
class ApplicationConfigTest {

  @Test
  void flywayIsDisabledByDefault() throws IOException {
    var sources = new MutablePropertySources();
    sources.addFirst(load("application.yml"));
    var resolver = new PropertySourcesPropertyResolver(sources);

    assertThat(resolver.getProperty("spring.flyway.enabled")).isEqualTo("false");
    assertThat(resolver.getProperty("spring.flyway.user")).isNull();
    assertThat(resolver.getProperty("spring.flyway.password")).isNull();
  }

  @Test
  void onlyMigrateProfileReadsMigratorAccount() throws IOException {
    PropertySource<?> migrate = load("application-migrate.yml");

    assertThat(raw(migrate, "spring.flyway.enabled")).isEqualTo("true");
    assertThat(raw(migrate, "spring.main.web-application-type")).isEqualTo("none");
    assertThat(raw(migrate, "spring.flyway.user")).isEqualTo("${SIGNA_DB_MIGRATOR_USER}");
    assertThat(raw(migrate, "spring.flyway.password")).isEqualTo("${SIGNA_DB_MIGRATOR_PASSWORD}");

    for (String other : new String[] {"application.yml", "application-local.yml"}) {
      String text =
          new ClassPathResource(other).getContentAsString(StandardCharsets.UTF_8).toUpperCase();
      assertThat(text).as(other).doesNotContain("SIGNA_DB_MIGRATOR");
    }
  }

  private static PropertySource<?> load(String name) throws IOException {
    return new YamlPropertySourceLoader().load(name, new ClassPathResource(name)).getFirst();
  }

  private static String raw(PropertySource<?> source, String key) {
    Object value = source.getProperty(key);
    return value == null ? null : value.toString();
  }
}
