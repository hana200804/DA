package vn.signa.identity.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.identity.api.AuthenticatedUser;
import vn.signa.identity.internal.UserAdminService;
import vn.signa.identity.internal.UserPage;
import vn.signa.identity.internal.UserSearch;
import vn.signa.identity.internal.UserStatus;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;

/** Quản lý người dùng cho web quản trị (docs/07 §8, FR-36). */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasAuthority('user:manage')")
class AdminUserController {

  /** Vai trò theo docs/06 §4. */
  static final String ROLES = "USER|MODERATOR|ADMIN";

  /** {@code null} là giữ nguyên; phải có ít nhất một trường. */
  record UpdateUser(
      @Pattern(regexp = ROLES, message = "phải là " + ROLES) String role, UserStatus status) {}

  private final UserAdminService service;

  AdminUserController(UserAdminService service) {
    this.service = service;
  }

  @GetMapping
  UserPage list(
      @RequestParam(required = false) @Size(max = 255) String q,
      @RequestParam(required = false) @Pattern(regexp = ROLES) String role,
      @RequestParam(required = false) UserStatus status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.search(new UserSearch(q, role, status, page, size));
  }

  @PatchMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void update(
      @PathVariable String id,
      @Valid @RequestBody UpdateUser body,
      @AuthenticationPrincipal AuthenticatedUser actor) {
    if (body.role() == null && body.status() == null) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          ErrorCode.VALIDATION_ERROR.defaultMessage(),
          List.of(new FieldIssue("role", "cần ít nhất một trong role, status")),
          null);
    }
    service.update(actor.userId(), id, body.role(), body.status());
  }
}
