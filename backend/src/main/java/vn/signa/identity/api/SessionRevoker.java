package vn.signa.identity.api;

/** Thu hồi phiên. Lát 8 gọi khi khoá tài khoản hoặc đổi vai trò (FR-36). */
public interface SessionRevoker {

  /** Xoá mọi phiên đang mở của người dùng; request sau đó với các token cũ nhận {@code 401}. */
  void revokeAll(String userId);
}
