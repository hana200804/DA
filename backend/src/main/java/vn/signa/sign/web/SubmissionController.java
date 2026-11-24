package vn.signa.sign.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.signa.identity.api.AuthenticatedUser;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.sign.internal.SubmissionService;
import vn.signa.sign.internal.SubmissionService.Page;
import vn.signa.sign.internal.SubmissionService.Receipt;

@RestController
class SubmissionController {

  private final SubmissionService submissions;

  SubmissionController(SubmissionService submissions) {
    this.submissions = submissions;
  }

  @PostMapping(path = "/api/v1/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAuthority('submission:create')")
  ResponseEntity<Receipt> submit(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestPart(value = "metadata", required = false) byte[] metadata,
      @RequestPart(value = "crop", required = false) MultipartFile crop)
      throws IOException {
    if (crop != null && crop.getSize() > SubmissionService.MAX_CROP_BYTES) {
      throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
    }
    var result = submissions.submit(user.userId(), metadata, crop == null ? null : crop.getBytes());
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result.receipt());
  }

  @GetMapping("/api/v1/me/submissions")
  Page list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return submissions.list(user.userId(), page, size);
  }
}
