package vn.signa.sign.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;
import vn.signa.shared.error.FieldIssue;
import vn.signa.sign.internal.places.PlaceSearch;
import vn.signa.sign.internal.places.PlacesRateLimiter;

@RestController
class PlacesController {

  private final PlaceSearch places;
  private final PlacesRateLimiter limiter;

  PlacesController(PlaceSearch places, PlacesRateLimiter limiter) {
    this.places = places;
    this.limiter = limiter;
  }

  @GetMapping("/api/v1/places/search")
  PlaceSearch.Result search(@RequestParam String q, HttpServletRequest request) {
    String query = q.strip();
    int length = query.codePointCount(0, query.length());
    if (length < 2 || length > 100) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          ErrorCode.VALIDATION_ERROR.defaultMessage(),
          List.of(new FieldIssue("q", "phải dài 2–100 ký tự sau khi bỏ khoảng trắng hai đầu")),
          null);
    }
    limiter.acquire(request.getRemoteAddr());
    return places.search(query);
  }
}
