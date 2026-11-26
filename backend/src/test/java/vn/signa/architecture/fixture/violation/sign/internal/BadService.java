package vn.signa.architecture.fixture.violation.sign.internal;

import vn.signa.architecture.fixture.violation.identity.internal.SecretRepository;

/** Lớp mẫu vi phạm: sign dùng thẳng identity.internal. */
public class BadService {
  SecretRepository repository = new SecretRepository();
}
