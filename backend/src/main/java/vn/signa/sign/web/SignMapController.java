package vn.signa.sign.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.shared.error.ApiException;
import vn.signa.sign.internal.map.MapQueryParser;
import vn.signa.sign.internal.map.MapResult;
import vn.signa.sign.internal.map.MapService;
import vn.signa.sign.internal.map.SignDetail;

/** Bản đồ công khai: biển trong vùng và chi tiết biển (docs/07 §6). */
@RestController
@RequestMapping("/api/v1/signs")
public class SignMapController {

  /**
   * Biển đổi trạng thái ngay khi kiểm duyệt viên quyết định, và web quản trị tải lại bản đồ sau khi
   * sửa biển; chưa có ETag nên trình duyệt phải hỏi lại server mỗi lần. Dữ liệu công khai nên được
   * phép lưu (ví dụ cho nút quay lại), khác {@code no-store} mặc định của Spring Security.
   */
  static final CacheControl SIGNS_CACHE = CacheControl.noCache();

  /**
   * Chi tiết chứa URL ký 10 phút tới ảnh riêng tư, và từ lát 7 có đánh giá của người đang đăng
   * nhập: không lưu ở bất kỳ bộ nhớ đệm nào.
   */
  static final CacheControl DETAIL_CACHE = CacheControl.noStore();

  private final MapService service;

  public SignMapController(MapService service) {
    this.service = service;
  }

  /**
   * Tham số nhận dạng chuỗi để mọi lỗi đi qua {@link MapQueryParser} và có {@code details} theo
   * docs/07 §2.
   */
  @GetMapping
  public ResponseEntity<MapResult> signs(
      @RequestParam(required = false) String bbox,
      @RequestParam(required = false) String categories,
      @RequestParam(required = false) String signTypeCodes,
      @RequestParam(required = false) String zoom) {
    MapResult result = service.find(MapQueryParser.parse(bbox, categories, signTypeCodes, zoom));
    return ResponseEntity.ok().cacheControl(SIGNS_CACHE).body(result);
  }

  @GetMapping("/{id}")
  public ResponseEntity<SignDetail> detail(@PathVariable String id) {
    SignDetail detail = service.detail(id).orElseThrow(ApiException::notFound);
    return ResponseEntity.ok().cacheControl(DETAIL_CACHE).body(detail);
  }
}
