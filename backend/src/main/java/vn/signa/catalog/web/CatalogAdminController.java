package vn.signa.catalog.web;

import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import vn.signa.catalog.internal.CatalogAdminRequests.EditCategory;
import vn.signa.catalog.internal.CatalogAdminRequests.EditType;
import vn.signa.catalog.internal.CatalogAdminRequests.NewCategory;
import vn.signa.catalog.internal.CatalogAdminRequests.NewType;
import vn.signa.catalog.internal.CatalogAdminService;
import vn.signa.catalog.internal.CatalogResponses.AdminSignTypeItem;
import vn.signa.catalog.internal.CatalogResponses.AdminSignTypeList;
import vn.signa.catalog.internal.CatalogResponses.SignCategoryItem;
import vn.signa.identity.api.AuthenticatedUser;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

/** API quản trị nhóm và loại biển (docs/07 §8, FR-35). */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAuthority('catalog:manage')")
class CatalogAdminController {

  private static final long MAX_ICON_BYTES = 1024 * 1024;

  private final CatalogAdminService catalog;

  CatalogAdminController(CatalogAdminService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/sign-types")
  AdminSignTypeList types(@RequestParam(required = false) Boolean active) {
    return catalog.types(active);
  }

  @PostMapping("/sign-categories")
  @ResponseStatus(HttpStatus.CREATED)
  SignCategoryItem createCategory(
      @AuthenticationPrincipal AuthenticatedUser user, @RequestBody @Valid NewCategory input) {
    return catalog.createCategory(user.userId(), input);
  }

  @PatchMapping("/sign-categories/{code}")
  SignCategoryItem editCategory(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String code,
      @RequestBody @Valid EditCategory input) {
    return catalog.editCategory(user.userId(), code, input);
  }

  @PostMapping("/sign-types")
  @ResponseStatus(HttpStatus.CREATED)
  AdminSignTypeItem createType(
      @AuthenticationPrincipal AuthenticatedUser user, @RequestBody @Valid NewType input) {
    return catalog.createType(user.userId(), input);
  }

  @PatchMapping("/sign-types/{code}")
  AdminSignTypeItem editType(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String code,
      @RequestBody @Valid EditType input) {
    return catalog.editType(user.userId(), code, input);
  }

  @PutMapping(value = "/sign-types/{code}/icon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void uploadIcon(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String code,
      @RequestPart("icon") MultipartFile icon)
      throws IOException {
    if (icon.getSize() > MAX_ICON_BYTES) {
      throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Ảnh minh hoạ vượt quá 1 MB");
    }
    catalog.uploadIcon(user.userId(), code, icon.getBytes());
  }
}
