package vn.signa.identity.internal;

import java.time.Instant;

/** Nội dung {@code session:{hash}} trong Redis (docs/06 §10). */
record Session(String userId, String role, Client client, Instant createdAt) {}
