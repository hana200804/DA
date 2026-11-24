package vn.signa.recognition.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.signa.identity.api.AuthenticatedUser;

class PrincipalCallerIdentityTest {

  private final PrincipalCallerIdentity caller = new PrincipalCallerIdentity();

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void noAuthenticationIsGuest() {
    assertThat(caller.userId()).isEmpty();
  }

  @Test
  void anonymousAuthenticationIsGuest() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

    assertThat(caller.userId()).isEmpty();
  }

  @Test
  void signedInUserIsIdentifiedByAccountId() {
    AuthenticatedUser user = new AuthenticatedUser("u-42", "USER", Set.of("submission:create"));
    TestingAuthenticationToken token = new TestingAuthenticationToken(user, null);
    token.setAuthenticated(true);
    SecurityContextHolder.getContext().setAuthentication(token);

    assertThat(caller.userId()).contains("u-42");
  }

  @Test
  void otherPrincipalTypesAreGuest() {
    TestingAuthenticationToken token = new TestingAuthenticationToken("someone", null);
    token.setAuthenticated(true);
    SecurityContextHolder.getContext().setAuthentication(token);

    assertThat(caller.userId()).isEmpty();
  }
}
