package vn.signa.sign.web.admin.reports;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.identity.api.AuthenticatedUser;
import vn.signa.sign.internal.feedback.ReportService;
import vn.signa.sign.internal.feedback.ReportService.Group;
import vn.signa.sign.internal.feedback.ReportService.Page;
import vn.signa.sign.internal.feedback.ReportService.Reason;
import vn.signa.sign.internal.feedback.ReportService.Status;

@RestController
@RequestMapping("/api/v1/admin/reports")
@PreAuthorize("hasAuthority('report:resolve')")
class AdminReportController {

  private final ReportService reports;

  AdminReportController(ReportService reports) {
    this.reports = reports;
  }

  @GetMapping
  Page<Group> queue(
      @RequestParam(defaultValue = "PENDING") Status status,
      @RequestParam(required = false) Reason reason,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return reports.queue(status, reason, page, size);
  }

  @PostMapping("/{id}/dismiss")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void dismiss(
      @PathVariable String id,
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody DismissRequest request) {
    reports.dismiss(id, user.userId(), request.note());
  }

  record DismissRequest(@NotBlank @Size(max = 1000) String note) {}
}
