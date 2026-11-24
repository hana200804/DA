package vn.signa.sign.web.admin;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.signa.identity.internal.RolePermissions;
import vn.signa.identity.internal.SessionService;
import vn.signa.identity.web.WithApiSecurity;
import vn.signa.sign.internal.stats.StatisticsService;
import vn.signa.sign.internal.stats.StatisticsView;
import vn.signa.sign.internal.stats.StatisticsView.DailySubmissions;
import vn.signa.sign.internal.stats.StatisticsView.Decisions;
import vn.signa.sign.internal.stats.StatisticsView.ProcessingTime;

@WebMvcTest(StatisticsController.class)
@WithApiSecurity
class StatisticsControllerTest {

  private static final String PATH = "/api/v1/admin/stats";
  private static final String TOKEN = "t".repeat(43);

  @Autowired MockMvc mvc;
  @Autowired SessionService sessions;
  @Autowired RolePermissions permissions;
  @MockitoBean StatisticsService statistics;

  @BeforeEach
  void authenticate() {
    when(sessions.authenticate(TOKEN))
        .thenReturn(Optional.of(new SessionService.ActiveSession("s1", "u1", "ADMIN")));
    when(permissions.of("ADMIN")).thenReturn(Set.of("stats:read"));
  }

  @Test
  void parsesIsoInstantsAndReturnsStatisticsSchema() throws Exception {
    when(statistics.statistics(
            Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-16T00:00:00Z")))
        .thenReturn(
            new StatisticsView(
                List.of(new DailySubmissions(LocalDate.of(2026, 1, 15), 4)),
                new Decisions(2, 1),
                new ProcessingTime(null, 0),
                List.of(),
                List.of()));

    mvc.perform(
            get(PATH)
                .param("from", "2026-01-15T07:00:00+07:00")
                .param("to", "2026-01-16T00:00:00Z")
                .header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.submissionsByDay[0].date").value("2026-01-15"))
        .andExpect(jsonPath("$.submissionsByDay[0].count").value(4))
        .andExpect(jsonPath("$.decisions.approved").value(2))
        .andExpect(jsonPath("$.decisions.rejected").value(1))
        .andExpect(jsonPath("$.processingTime.averageMs").value(nullValue()))
        .andExpect(jsonPath("$.processingTime.samples").value(0))
        .andExpect(jsonPath("$.signsByCategory").isArray())
        .andExpect(jsonPath("$.densityCells").isArray());
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-01-15", "1768435200000", "Thu, 15 Jan 2026 00:00:00 GMT"})
  void rejectsNonIsoInstant(String from) throws Exception {
    mvc.perform(get(PATH).param("from", from).header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.details[0].field").value("from"));
    verifyNoInteractions(statistics);
  }
}
