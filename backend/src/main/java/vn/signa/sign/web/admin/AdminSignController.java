package vn.signa.sign.web.admin;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.identity.api.AuthenticatedUser;
import vn.signa.shared.error.ApiException;
import vn.signa.sign.internal.review.ReviewQueryService;
import vn.signa.sign.internal.review.ReviewRequests.Approve;
import vn.signa.sign.internal.review.ReviewRequests.Edit;
import vn.signa.sign.internal.review.ReviewRequests.Keep;
import vn.signa.sign.internal.review.ReviewRequests.NewSign;
import vn.signa.sign.internal.review.ReviewRequests.Reasoned;
import vn.signa.sign.internal.review.ReviewService;
import vn.signa.sign.internal.review.ReviewViews.Created;
import vn.signa.sign.internal.review.ReviewViews.Detail;

/**
 * Chi tiết, duyệt, từ chối, giữ, gỡ, thêm và sửa biển (FR-31, FR-33). Thao tác trên biển đã có gửi
 * {@code version}; lệch thì {@code 409 SIGN_ALREADY_PROCESSED}.
 */
@RestController
@RequestMapping("/api/v1/admin/signs")
class AdminSignController {

  private final ReviewQueryService queries;
  private final ReviewService reviews;

  AdminSignController(ReviewQueryService queries, ReviewService reviews) {
    this.queries = queries;
    this.reviews = reviews;
  }

  /** Chứa URL ký 10 phút tới ảnh riêng tư: không lưu ở bộ nhớ đệm nào. */
  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('review:decide')")
  ResponseEntity<Detail> detail(@PathVariable String id) {
    Detail detail = queries.detail(id).orElseThrow(ApiException::notFound);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(detail);
  }

  @PostMapping("/{id}/approve")
  @PreAuthorize("hasAuthority('review:decide')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void approve(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id,
      @RequestBody @Valid Approve request) {
    reviews.approve(user.userId(), id, request);
  }

  @PostMapping("/{id}/reject")
  @PreAuthorize("hasAuthority('review:decide')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void reject(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id,
      @RequestBody @Valid Reasoned request) {
    reviews.reject(user.userId(), id, request);
  }

  @PostMapping("/{id}/keep")
  @PreAuthorize("hasAuthority('review:decide')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void keep(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id,
      @RequestBody @Valid Keep request) {
    reviews.keep(user.userId(), id, request);
  }

  @PostMapping("/{id}/remove")
  @PreAuthorize("hasAuthority('sign:edit')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void remove(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id,
      @RequestBody @Valid Reasoned request) {
    reviews.remove(user.userId(), id, request);
  }

  @PostMapping
  @PreAuthorize("hasAuthority('sign:edit')")
  @ResponseStatus(HttpStatus.CREATED)
  Created create(
      @AuthenticationPrincipal AuthenticatedUser user, @RequestBody @Valid NewSign request) {
    return reviews.create(user.userId(), request);
  }

  @PatchMapping("/{id}")
  @PreAuthorize("hasAuthority('sign:edit')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void edit(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id,
      @RequestBody @Valid Edit request) {
    reviews.edit(user.userId(), id, request);
  }
}
