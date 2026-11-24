package vn.signa.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.signa.catalog.internal.CatalogRepository.TypeRow;

class CatalogRepositoryTest {

  @Test
  void typeWritesPassRowFieldsAndTimestampThroughOneNamedParameterSource() {
    var jdbc = mock(NamedParameterJdbcOperations.class);
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    var repository = new CatalogRepository(JdbcClient.create(jdbc));
    var row = new TypeRow("T.1", "OTHER", "Biển thử", null, 50, null, null, null, null, true);
    var now = Instant.parse("2026-11-24T00:00:00Z");

    repository.insertType(row, now);
    repository.updateLabelFields(row, now);
    repository.updateAdminFields(row, now);

    var sources = ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc, times(3)).update(anyString(), sources.capture());
    assertThat(sources.getAllValues())
        .allSatisfy(params -> {
          assertThat(params.getValue("code")).isEqualTo("T.1");
          assertThat(params.getValue("labelId")).isNull();
          assertThat(params.getValue("now")).isEqualTo(Timestamp.from(now));
        });
  }
}
