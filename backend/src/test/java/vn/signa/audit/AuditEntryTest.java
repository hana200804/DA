package vn.signa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.signa.audit.api.ActorType;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.api.AuditEntry;

class AuditEntryTest {

  @Test
  void systemEntryHasNoActor() {
    AuditEntry entry =
        AuditEntry.bySystem(
            AuditEntityType.USER, "u-1", "ROLE_CHANGED", null, Map.of("role", "ADMIN"), null);

    assertThat(entry.actorType()).isEqualTo(ActorType.SYSTEM);
    assertThat(entry.actorId()).isNull();
  }

  @Test
  void systemEntryRejectsActorId() {
    assertThatThrownBy(() ->
            new AuditEntry(
                AuditEntityType.USER, "u-1", "LOCKED", ActorType.SYSTEM, "u-2", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void userEntryRequiresActorId() {
    assertThatThrownBy(
            () -> AuditEntry.byUser(" ", AuditEntityType.SIGN, "s-1", "APPROVED", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsBlankEntityIdAndTooLongFields() {
    assertThatThrownBy(
            () -> AuditEntry.bySystem(AuditEntityType.SIGN, "", "CREATED", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            AuditEntry.bySystem(AuditEntityType.SIGN, "s-1", "A".repeat(41), null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            AuditEntry.bySystem(
                AuditEntityType.SIGN, "s-1", "CREATED", null, null, "x".repeat(1001)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void entityTypesMatchSchemaEnum() {
    assertThat(AuditEntityType.values())
        .extracting(Enum::name)
        .containsExactly("SIGN", "REPORT", "SIGN_TYPE", "SIGN_CATEGORY", "USER");
  }
}
