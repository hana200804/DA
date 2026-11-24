package vn.signa.sign.web.admin;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.sign.internal.review.ReviewQueries;
import vn.signa.sign.internal.review.ReviewQueryService;
import vn.signa.sign.internal.review.ReviewViews.QueuePage;

/** Hàng đợi phê duyệt (FR-21, FR-31). */
@RestController
class ReviewQueueController {

  private final ReviewQueryService queries;

  ReviewQueueController(ReviewQueryService queries) {
    this.queries = queries;
  }

  /** Tham số nhận dạng chuỗi để mọi lỗi đi qua {@link ReviewQueries} và có {@code details}. */
  @GetMapping("/api/v1/admin/review-queue")
  @PreAuthorize("hasAuthority('review:decide')")
  ResponseEntity<QueuePage> queue(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String suggestion,
      @RequestParam(required = false) String categories,
      @RequestParam(required = false) String bbox,
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String size) {
    QueuePage result =
        queries.queue(ReviewQueries.parse(status, suggestion, categories, bbox, page, size));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
  }
}
