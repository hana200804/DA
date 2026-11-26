package vn.signa.sign.internal.review;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import vn.signa.sign.internal.review.ReviewViews.Detail;
import vn.signa.sign.internal.review.ReviewViews.LatLng;
import vn.signa.sign.internal.review.ReviewViews.NearbySign;
import vn.signa.sign.internal.review.ReviewViews.SubmissionView;

/** Chi tiết biển mẫu cho test controller và ma trận quyền. */
public final class ReviewDetails {

  public static final Instant CREATED = Instant.parse("2026-01-15T08:30:12.345Z");

  private ReviewDetails() {}

  public static Detail sample(String id) {
    LatLng here = new LatLng(21.0285, 105.8048);
    return new Detail(
        id,
        "P.123a",
        "Cấm rẽ trái",
        "PROHIBITORY",
        here,
        92,
        "PENDING",
        0.82,
        "APPROVE",
        2,
        List.of(),
        CREATED,
        7,
        false,
        null,
        "https://minio/cover.jpg",
        0,
        0,
        Map.of("P.123a", 1.8),
        List.of(
            new SubmissionView(
                "sub-1",
                "P.123a",
                0.9,
                0.9,
                "CAMERA",
                List.of(),
                92,
                new LatLng(21.02851, 105.80479),
                CREATED.plusMillis(657),
                "GROUPED",
                "https://minio/sub-1.jpg")),
        List.of(new NearbySign("near-1", "P.123b", new LatLng(21.0286, 105.8049), "APPROVED")));
  }
}
