package vn.signa.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LikePatternTest {

  @Test
  void blankMeansNoFilter() {
    assertThat(LikePattern.contains(null)).isNull();
    assertThat(LikePattern.contains("")).isNull();
    assertThat(LikePattern.contains("   ")).isNull();
  }

  @Test
  void trimsAndLowercases() {
    assertThat(LikePattern.contains("  Nguyen.A@Signa.VN ")).isEqualTo("%nguyen.a@signa.vn%");
  }

  @Test
  void escapesWildcardsAndTheEscapeCharacter() {
    assertThat(LikePattern.contains("_")).isEqualTo("%!_%");
    assertThat(LikePattern.contains("%")).isEqualTo("%!%%");
    assertThat(LikePattern.contains("a_b%c!d")).isEqualTo("%a!_b!%c!!d%");
    assertThat(LikePattern.contains("\\")).isEqualTo("%\\%");
  }

  @Test
  void whereUsesExplicitEscapeAndOnlyPresentFilters() {
    var where = UserRepository.Where.of(new UserSearch(" A_B ", "ADMIN", UserStatus.LOCKED, 0, 20));
    assertThat(where.sql())
        .isEqualTo(
            " WHERE email LIKE :email ESCAPE '!' AND role_code = :role AND status = :status");
    assertThat(where.params())
        .containsEntry("email", "%a!_b%")
        .containsEntry("role", "ADMIN")
        .containsEntry("status", "LOCKED");
    assertThat(UserRepository.Where.of(new UserSearch(null, null, null, 0, 20)).sql()).isEmpty();
  }
}
