package vn.signa.identity.internal;

import java.util.Optional;

/**
 * Kho phiên theo mã phiên ({@link SessionTokens#hash}). Cài đặt thật dùng Redis; test unit dùng bản
 * trong bộ nhớ.
 */
interface SessionStore {

  /**
   * Lưu phiên với TTL {@code session.client().ttl()} và thêm vào danh sách phiên của người dùng.
   */
  void create(String sessionId, Session session);

  /** Đọc phiên và gia hạn TTL theo client; rỗng nếu không có hoặc đã hết hạn. */
  Optional<Session> findAndTouch(String sessionId);

  /** Xoá một phiên. */
  void delete(String sessionId);

  /** Xoá mọi phiên của người dùng, trừ {@code keepSessionId} nếu khác {@code null}. */
  void deleteAllOf(String userId, String keepSessionId);
}
