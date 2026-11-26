package vn.signa.identity.internal;

import java.time.Instant;

/** Nội dung {@code session:{hash}} trong Redis (docs/06 §10). */
record Session(String userId, int credentialVersion, Client client, Instant createdAt) {}
