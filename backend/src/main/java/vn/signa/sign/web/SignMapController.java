package vn.signa.sign.web;

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

  private final MapService service;

  public SignMapController(MapService service) {
    this.service = service;
  }

  /**
   * Tham số nhận dạng chuỗi để mọi lỗi đi qua {@link MapQueryParser} và có {@code details} theo
   * docs/07 §2.
   */
  @GetMapping
  public MapResult signs(
      @RequestParam(required = false) String bbox,
      @RequestParam(required = false) String categories,
      @RequestParam(required = false) String signTypeCodes,
      @RequestParam(required = false) String zoom) {
    return service.find(MapQueryParser.parse(bbox, categories, signTypeCodes, zoom));
  }

  @GetMapping("/{id}")
  public SignDetail detail(@PathVariable String id) {
    return service.detail(id).orElseThrow(ApiException::notFound);
  }
}
