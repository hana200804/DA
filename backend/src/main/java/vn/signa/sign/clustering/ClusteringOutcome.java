package vn.signa.sign.clustering;

import java.math.BigDecimal;
import java.util.List;

/**
 * Kết quả gom một đề xuất.
 *
 * @param sign biển đích sau khi xử lý (mới hoặc đã có)
 * @param signCreated biển được tạo mới, cần {@code INSERT}
 * @param signChanged biển, trọng số hoặc thành viên đổi, cần ghi lại; {@code false} khi đề xuất bị
 *     từ chối vì biển cùng mã đã bị từ chối
 * @param submissionWeight trọng số {@code w} của đề xuất, 4 chữ số thập phân
 * @param submissionStatus trạng thái mới của đề xuất
 * @param rejectReason lý do khi {@code REJECTED}
 * @param memberUpdates đề xuất thành viên khác đổi trạng thái (khi tự duyệt)
 * @param events lịch sử cần ghi, theo thứ tự
 */
public record ClusteringOutcome(
    SignState sign,
    boolean signCreated,
    boolean signChanged,
    BigDecimal submissionWeight,
    SubmissionStatus submissionStatus,
    String rejectReason,
    List<MemberUpdate> memberUpdates,
    List<SignEvent> events) {

  public ClusteringOutcome {
    memberUpdates = List.copyOf(memberUpdates);
    events = List.copyOf(events);
  }
}
