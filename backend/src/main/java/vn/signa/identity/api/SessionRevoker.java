package vn.signa.identity.api;

/** Thu hồi phiên. Lát 8 gọi khi khoá tài khoản hoặc đổi vai trò (FR-36). */
public interface SessionRevoker {

  /** Sau commit, dọn các phiên trước phiên bản xác thực vừa ghi vào MySQL. */
  void revokeOlderAfterCommit(String userId, int credentialVersion);
}
