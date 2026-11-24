package vn.signa.sign.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.identity.api.AuthenticatedUser;
import vn.signa.sign.clustering.RatingValue;
import vn.signa.sign.internal.feedback.RatingService;

@RestController
@RequestMapping("/api/v1")
class SignFeedbackController {

  private final RatingService ratings;

  SignFeedbackController(RatingService ratings) {
    this.ratings = ratings;
  }

  @PutMapping("/signs/{id}/rating")
  @PreAuthorize("hasAuthority('sign:rate')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void rate(
      @PathVariable String id,
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody RatingRequest request) {
    ratings.rate(id, user.userId(), RatingValue.valueOf(request.value()));
  }

  record RatingRequest(@NotNull @Pattern(regexp = "CONFIRM|DENY") String value) {}
}
