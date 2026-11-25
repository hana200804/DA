package vn.signa.recognition.web;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.signa.identity.api.AuthenticatedUser;
import vn.signa.recognition.internal.CompareResult;
import vn.signa.recognition.internal.RecognitionService;

@RestController
class RecognitionComparisonController {

  private final RecognitionService recognition;

  RecognitionComparisonController(RecognitionService recognition) {
    this.recognition = recognition;
  }

  @PostMapping(
      path = "/api/v1/admin/recognition/compare",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAuthority('recognition:compare')")
  CompareResult compare(
      @RequestPart("image") MultipartFile image, @AuthenticationPrincipal AuthenticatedUser user) {
    return recognition.compare(image, user.userId());
  }
}
