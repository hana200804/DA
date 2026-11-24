package vn.signa.sign.clustering;

/** Trạng thái mới của một đề xuất thành viên; {@code rejectReason} chỉ có khi {@code REJECTED}. */
public record MemberUpdate(String submissionId, SubmissionStatus status, String rejectReason) {}
