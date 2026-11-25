package vn.signa.sign.internal.review;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Response của hàng đợi và chi tiết biển (docs/07 §8). */
public final class ReviewViews {

  private ReviewViews() {}

  public record LatLng(double lat, double lng) {}

  /** Một mục hàng đợi. */
  public record QueueItem(
      String id,
      String signTypeCode,
      String signName,
      String categoryCode,
      LatLng location,
      Integer headingDeg,
      String status,
      double confidence,
      String suggestion,
      int distinctSubmitters,
      List<String> flags,
      Instant createdAt) {}

  public record QueuePage(List<QueueItem> items, int page, int size, long total) {}

  public record SubmissionView(
      String id,
      String signTypeCode,
      double confidence,
      Double weight,
      String source,
      List<String> flags,
      Integer headingDeg,
      LatLng location,
      Instant receivedAt,
      String status,
      String cropUrl) {}

  public record NearbySign(String id, String signTypeCode, LatLng location, String status) {}

  /** Chi tiết: mọi trường của mục hàng đợi và các trường bổ sung. */
  public record Detail(
      String id,
      String signTypeCode,
      String signName,
      String categoryCode,
      LatLng location,
      Integer headingDeg,
      String status,
      double confidence,
      String suggestion,
      int distinctSubmitters,
      List<String> flags,
      Instant createdAt,
      int version,
      boolean locationLocked,
      String relatedSignId,
      String coverCropUrl,
      int confirmCount,
      int denyCount,
      Map<String, Double> codeWeights,
      List<SubmissionView> submissions,
      List<NearbySign> nearbySigns) {}

  /** {@code POST /admin/signs}. */
  public record Created(String id, int version) {}
}
