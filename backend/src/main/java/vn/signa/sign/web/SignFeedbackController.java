package vn.signa.sign.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.identity.api.AuthenticatedUser;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.sign.clustering.RatingValue;
import vn.signa.sign.internal.feedback.RatingService;
import vn.signa.sign.internal.feedback.ReportMetadata;
import vn.signa.sign.internal.feedback.ReportService;

@RestController
@RequestMapping("/api/v1")
class SignFeedbackController {

  private final RatingService ratings;
  private final ReportService reports;
  private final JsonMapper json;

  SignFeedbackController(RatingService ratings, ReportService reports, JsonMapper json) {
    this.ratings = ratings;
    this.reports = reports;
    this.json = json;
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

  @PostMapping(path = "/signs/{id}/reports", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasAuthority('report:create')")
  @ResponseStatus(HttpStatus.CREATED)
  ReportService.Receipt create(
      @PathVariable String id,
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestBody JsonNode metadata) {
    return reports.create(id, user.userId(), ReportMetadata.read(metadata), null);
  }

  @PostMapping(path = "/signs/{id}/reports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAuthority('report:create')")
  @ResponseStatus(HttpStatus.CREATED)
  ReportService.Receipt createWithPhoto(
      @PathVariable String id,
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestPart("metadata") byte[] metadata,
      @RequestPart("photo") MultipartFile photo)
      throws IOException {
    if (photo.getSize() > ReportService.MAX_PHOTO_BYTES) {
      throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
    }
    JsonNode body;
    try {
      body = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(metadata);
    } catch (JacksonException e) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, "metadata phải là JSON hợp lệ");
    }
    return reports.create(id, user.userId(), ReportMetadata.read(body), photo.getBytes());
  }

  @GetMapping("/me/reports")
  ReportService.Page<ReportService.Mine> mine(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return reports.mine(user.userId(), page, size);
  }

  record RatingRequest(@NotNull @Pattern(regexp = "CONFIRM|DENY") String value) {}
}
