package vn.signa.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.catalog.internal.CatalogQueryService;
import vn.signa.catalog.internal.CatalogResponses.SignCategoryList;
import vn.signa.catalog.internal.CatalogResponses.SignTypeItem;
import vn.signa.catalog.internal.CatalogResponses.SignTypeList;
import vn.signa.catalog.internal.CatalogResponses.SignTypesSnapshot;
import vn.signa.shared.error.ApiException;

/** Danh mục biển công khai (docs/07 §4, FR-08). */
@RestController
@RequestMapping("/api/v1")
class CatalogController {

  private final CatalogQueryService catalog;

  CatalogController(CatalogQueryService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/sign-categories")
  SignCategoryList categories() {
    return catalog.categories();
  }

  /**
   * Toàn bộ loại đang dùng. {@code If-None-Match} khớp ETag thì Spring trả {@code 304} không body
   * (HttpEntityMethodProcessor kiểm tra ETag của {@code ResponseEntity} với GET, HEAD).
   */
  @GetMapping("/sign-types")
  @Operation(summary = "Toàn bộ loại biển đang dùng; hỗ trợ If-None-Match")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = SignTypeList.class)))
  @ApiResponse(responseCode = "304", description = "ETag không đổi")
  ResponseEntity<byte[]> signTypes() {
    SignTypesSnapshot snapshot = catalog.signTypes();
    return ResponseEntity.ok()
        .eTag(snapshot.etag())
        .cacheControl(CacheControl.noCache())
        .contentType(MediaType.APPLICATION_JSON)
        .body(snapshot.body());
  }

  @GetMapping("/sign-types/{code}")
  SignTypeItem signType(@PathVariable String code) {
    return catalog.activeType(code).orElseThrow(ApiException::notFound);
  }
}
