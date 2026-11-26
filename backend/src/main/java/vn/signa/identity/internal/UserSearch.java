package vn.signa.identity.internal;

/**
 * Bộ lọc của {@code GET /admin/users}. {@code q} là một phần email; độ dài và phạm vi {@code page},
 * {@code size} kiểm ở controller.
 */
public record UserSearch(String q, String role, UserStatus status, int page, int size) {

  public UserSearch {
    if (page < 0 || size < 1) {
      throw new IllegalArgumentException("page phải ≥ 0, size phải ≥ 1");
    }
    if (role != null && role.isBlank()) {
      role = null;
    }
  }

  long offset() {
    return (long) page * size;
  }
}
