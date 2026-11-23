package vn.signa.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JdbcAuditRecorderTest {

  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void nullStaysNull() {
    assertThat(JdbcAuditRecorder.toJson(json, null)).isNull();
  }

  @Test
  void writesChangedFieldsAsJsonObject() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("role", "ADMIN");
    value.put("previous", null);

    assertThat(JdbcAuditRecorder.toJson(json, value))
        .isEqualTo("{\"role\":\"ADMIN\",\"previous\":null}");
  }
}
