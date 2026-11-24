package vn.signa.sign.clustering;

import java.time.Instant;
import java.util.Objects;

/**
 * Một người đánh giá hoặc đánh giá lại biển (FR-09: mỗi người một đánh giá, ghi đè).
 *
 * @param previous đánh giá cũ của người này, {@code null} nếu chưa có
 * @param previousAt thời điểm của đánh giá cũ ({@code sign_ratings.updated_at})
 * @param current đánh giá mới
 * @param at thời điểm đánh giá mới
 */
public record RatingChange(
    RatingValue previous, Instant previousAt, RatingValue current, Instant at) {

  public RatingChange {
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(at, "at");
    if ((previous == null) != (previousAt == null)) {
      throw new IllegalArgumentException("previous và previousAt phải cùng có hoặc cùng không");
    }
  }
}
