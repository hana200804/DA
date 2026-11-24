package vn.signa.audit.api;

/**
 * Ghi lịch sử xử lý bất biến (FR-23, NFR-11). Phải gọi trong giao dịch của module gây ra thay đổi,
 * để lịch sử và thay đổi cùng commit hoặc cùng rollback; gọi ngoài giao dịch thì ném {@link
 * org.springframework.transaction.IllegalTransactionStateException}.
 */
public interface AuditRecorder {

  void record(AuditEntry entry);
}
