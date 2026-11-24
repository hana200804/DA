package vn.signa.sign.clustering;

import java.util.List;

/** Kết quả áp một đánh giá: trạng thái biển mới và lịch sử cần ghi. */
public record RatingOutcome(SignState sign, List<SignEvent> events) {

  public RatingOutcome {
    events = List.copyOf(events);
  }
}
