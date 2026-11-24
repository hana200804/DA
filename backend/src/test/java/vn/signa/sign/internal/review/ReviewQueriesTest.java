package vn.signa.sign.internal.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;
import vn.signa.sign.clustering.SignStatus;
import vn.signa.sign.clustering.Suggestion;
import vn.signa.sign.internal.map.BoundingBox;
import vn.signa.sign.internal.review.ReviewQueries.QueueQuery;

class ReviewQueriesTest {

  @Test
  void defaultsToBothReviewableStatusesFirstPage() {
    QueueQuery query = ReviewQueries.parse(null, null, null, null, null, null);

    assertThat(query.statuses())
        .containsExactlyInAnyOrder(SignStatus.PENDING, SignStatus.UNDER_REVIEW);
    assertThat(query.suggestion()).isNull();
    assertThat(query.categories()).isEmpty();
    assertThat(query.bbox()).isNull();
    assertThat(query.page()).isZero();
    assertThat(query.size()).isEqualTo(ReviewQueries.DEFAULT_SIZE);
    assertThat(query.offset()).isZero();
  }

  @Test
  void readsEveryFilter() {
    QueueQuery query =
        ReviewQueries.parse(
            "under_review",
            "approve",
            " PROHIBITORY, WARNING ,,PROHIBITORY",
            "105.8,21.02,105.81,21.03",
            "2",
            "50");

    assertThat(query.statuses()).containsExactly(SignStatus.UNDER_REVIEW);
    assertThat(query.suggestion()).isEqualTo(Suggestion.APPROVE);
    assertThat(query.categories()).containsExactly("PROHIBITORY", "WARNING");
    assertThat(query.bbox()).isEqualTo(new BoundingBox(105.8, 21.02, 105.81, 21.03));
    assertThat(query.offset()).isEqualTo(100);
    assertThat(ReviewQueries.parse("PENDING", "", "", " ", "", "").statuses())
        .isEqualTo(Set.of(SignStatus.PENDING));
  }

  @Test
  void collectsEveryInvalidParameter() {
    assertThatThrownBy(
            () -> ReviewQueries.parse("APPROVED", "MAYBE", "x".repeat(21), "1,2,3", "-1", "101"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
              assertThat(e.details())
                  .extracting(FieldIssue::field)
                  .containsExactly("status", "suggestion", "categories", "bbox", "page", "size");
            });
  }

  @Test
  void limitsCategoryCountAndRejectsNonNumbers() {
    String many =
        String.join(",", java.util.stream.IntStream.range(0, 51).mapToObj(i -> "C" + i).toList());
    assertThatThrownBy(() -> ReviewQueries.parse(null, null, many, null, "a", "0"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e ->
                assertThat(e.details())
                    .extracting(FieldIssue::field)
                    .containsExactly("categories", "page", "size"));
  }
}
