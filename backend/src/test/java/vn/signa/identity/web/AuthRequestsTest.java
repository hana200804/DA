package vn.signa.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import vn.signa.identity.internal.Client;

class AuthRequestsTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void emailIsTrimmedAndLowercased() {
    assertThat(new AuthRequests.Register(" Ánh@Ví.DỤ ", "12345678", "A").email())
        .isEqualTo("ánh@ví.dụ");
    assertThat(new AuthRequests.Login(" A@B.VN", "x", Client.WEB).email()).isEqualTo("a@b.vn");
  }

  @Test
  void validRegisterPasses() {
    assertThat(validator.validate(new AuthRequests.Register("a@b.vn", "12345678", "A"))).isEmpty();
    assertThat(
            validator.validate(
                new AuthRequests.Register("a@b.vn", "a".repeat(72), "a".repeat(100))))
        .isEmpty();
  }

  @Test
  void emailOver255IsRejected() {
    String email = "a".repeat(250) + "@b.vn";
    assertThat(validator.validate(new AuthRequests.Register(email, "12345678", "A")))
        .extracting(v -> v.getPropertyPath().toString())
        .contains("email");
  }

  @Test
  void toStringNeverContainsPasswords() {
    assertThat(new AuthRequests.Register("a@b.vn", "bi-mat-123", "A").toString())
        .doesNotContain("bi-mat-123");
    assertThat(new AuthRequests.Login("a@b.vn", "bi-mat-123", Client.WEB).toString())
        .doesNotContain("bi-mat-123");
    assertThat(new AuthRequests.ChangePassword("bi-mat-123", "moi-mat-khau").toString())
        .doesNotContain("bi-mat-123")
        .doesNotContain("moi-mat-khau");
  }
}
