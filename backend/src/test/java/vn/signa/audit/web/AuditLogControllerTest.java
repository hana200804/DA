package vn.signa.audit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.audit.api.ActorType;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.internal.AuditLogQuery;
import vn.signa.audit.internal.AuditLogService;
import vn.signa.audit.internal.AuditLogView;
import vn.signa.audit.internal.AuditPage;
import vn.signa.identity.internal.RolePermissions;
import vn.signa.identity.internal.SessionService;
import vn.signa.identity.web.SecuredWebMvc;

@WebMvcTest(controllers = AuditLogController.class)
@Import(SecuredWebMvc.class)
class AuditLogControllerTest {

  private static final String URL = "/api/v1/admin/audit-logs";

  @Autowired MockMvc mvc;
  @MockitoBean AuditLogService service;
  @MockitoBean SessionService sessions;
  @MockitoBean RolePermissions rolePermissions;

  private String moderator;
  private String user;

  @BeforeEach
  void signIn() {
    moderator = SecuredWebMvc.signIn(sessions, rolePermissions, "MODERATOR", Set.of("audit:read"));
    user = SecuredWebMvc.signIn(sessions, rolePermissions, "USER", Set.of("sign:rate"));
    when(service.search(any())).thenReturn(new AuditPage<>(List.of(), 0, 20, 0));
  }

  @Test
  void returnsRowsWithJsonObjects() throws Exception {
    JsonMapper json = JsonMapper.builder().build();
    AuditLogView row =
        new AuditLogView(
            7,
            AuditEntityType.USER,
            "u-2",
            "LOCKED",
            ActorType.USER,
            "u-1",
            "Quản trị",
            json.readTree("{\"status\":\"ACTIVE\"}"),
            json.readTree("{\"status\":\"LOCKED\"}"),
            null,
            "req-9",
            Instant.parse("2026-11-24T08:30:12.345Z"));
    when(service.search(any())).thenReturn(new AuditPage<>(List.of(row), 0, 20, 1));

    mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, moderator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.items[0].id").value(7))
        .andExpect(jsonPath("$.items[0].entityType").value("USER"))
        .andExpect(jsonPath("$.items[0].actorName").value("Quản trị"))
        .andExpect(jsonPath("$.items[0].beforeValue.status").value("ACTIVE"))
        .andExpect(jsonPath("$.items[0].afterValue.status").value("LOCKED"))
        .andExpect(jsonPath("$.items[0].requestId").value("req-9"))
        .andExpect(jsonPath("$.items[0].createdAt").value("2026-11-24T08:30:12.345Z"));
  }

  @Test
  void passesFiltersAndDefaults() throws Exception {
    mvc.perform(
            get(URL)
                .header(HttpHeaders.AUTHORIZATION, moderator)
                .param("entityType", "SIGN")
                .param("entityId", "s-1")
                .param("actorId", "u-1")
                .param("action", "APPROVED")
                .param("from", "2026-11-01T00:00:00.000Z")
                .param("to", "2026-11-30T23:59:59.999Z"))
        .andExpect(status().isOk());

    ArgumentCaptor<AuditLogQuery> captor = ArgumentCaptor.forClass(AuditLogQuery.class);
    verify(service).search(captor.capture());
    AuditLogQuery q = captor.getValue();
    assertThat(q)
        .isEqualTo(
            new AuditLogQuery(
                AuditEntityType.SIGN,
                "s-1",
                "u-1",
                "APPROVED",
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-30T23:59:59.999Z"),
                0,
                20));
  }

  @Test
  void badParametersAre400() throws Exception {
    String[][] bad = {
      {"size", "101"},
      {"size", "0"},
      {"page", "-1"},
      {"entityType", "BIEN"},
      {"from", "hôm qua"},
      {"action", "A".repeat(41)},
      {"entityId", "x".repeat(37)},
      {"actorId", "x".repeat(37)},
    };
    for (String[] param : bad) {
      mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, moderator).param(param[0], param[1]))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    verify(service, never()).search(any());
  }

  @Test
  void fromAfterToIs400OnFrom() throws Exception {
    mvc.perform(
            get(URL)
                .header(HttpHeaders.AUTHORIZATION, moderator)
                .param("from", "2026-11-02T00:00:00Z")
                .param("to", "2026-11-01T00:00:00Z"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.details[0].field").value("from"));
    verify(service, never()).search(any());
  }

  @Test
  void withoutAuditReadIs403AndGuestIs401() throws Exception {
    mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, user))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    verify(service, never()).search(any());
  }

  @Test
  void missingPermissionIs403EvenWithInvalidParameters() throws Exception {
    mvc.perform(
            get(URL)
                .header(HttpHeaders.AUTHORIZATION, user)
                .param("from", "2026-11-02T00:00:00Z")
                .param("to", "2026-11-01T00:00:00Z"))
        .andExpect(status().isForbidden());
    mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, user).param("size", "999"))
        .andExpect(status().isForbidden());
    mvc.perform(delete(URL + "/7").header(HttpHeaders.AUTHORIZATION, user))
        .andExpect(status().isForbidden());
    verify(service, never()).search(any());
  }

  @Test
  void historyCannotBeEditedOrDeleted() throws Exception {
    mvc.perform(delete(URL + "/7").header(HttpHeaders.AUTHORIZATION, moderator))
        .andExpect(status().isNotFound());
    mvc.perform(patch(URL + "/7").header(HttpHeaders.AUTHORIZATION, moderator))
        .andExpect(status().isNotFound());
    mvc.perform(delete(URL).header(HttpHeaders.AUTHORIZATION, moderator))
        .andExpect(status().isNotFound());
  }
}
