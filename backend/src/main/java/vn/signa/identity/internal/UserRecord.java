package vn.signa.identity.internal;

/** Một dòng {@code users}. {@code toString} không in hash mật khẩu. */
record UserRecord(
    String id,
    String email,
    String passwordHash,
    String displayName,
    String role,
    UserStatus status,
    int credentialVersion) {

  UserRecord(
      String id,
      String email,
      String passwordHash,
      String displayName,
      String role,
      UserStatus status) {
    this(id, email, passwordHash, displayName, role, status, 0);
  }

  @Override
  public String toString() {
    return "UserRecord[id=" + id + ", role=" + role + ", status=" + status + "]";
  }
}
