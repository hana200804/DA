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

  /** Đọc phiên; rỗng nếu không có hoặc đã hết hạn. */
  Optional<Session> find(String sessionId);

  /** Gia hạn phiên còn tồn tại. */
  boolean touch(String sessionId, Client client);

  /** Xoá một phiên. */
  void delete(String sessionId);

  /** Chuyển phiên hiện tại từ phiên bản liền trước, nếu phiên vẫn còn. */
  void promote(String userId, String sessionId, int credentialVersion);

  /** Xoá các phiên cũ hơn phiên bản đã commit. */
  void deleteOlder(String userId, int credentialVersion);
}
