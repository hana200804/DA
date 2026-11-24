package vn.signa.identity.internal;

import java.time.Instant;

/** Một tài khoản trong danh sách quản trị (docs/07 §8). Không có hash mật khẩu. */
public record ManagedUser(
    String id,
    String email,
    String displayName,
    String role,
    UserStatus status,
    Instant createdAt,
    Instant lastLoginAt) {}
