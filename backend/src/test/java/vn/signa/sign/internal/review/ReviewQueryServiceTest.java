package vn.signa.sign.internal.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.signa.catalog.api.SignCatalog;
import vn.signa.catalog.api.SignTypeView;
import vn.signa.shared.storage.Bucket;
import vn.signa.shared.storage.ObjectStorage;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.internal.review.ReviewQueries.QueueQuery;
import vn.signa.sign.internal.review.ReviewQueryRepository.NearbyRow;
import vn.signa.sign.internal.review.ReviewQueryRepository.SignRow;
import vn.signa.sign.internal.review.ReviewQueryRepository.SubmissionRow;
import vn.signa.sign.internal.review.ReviewViews.Detail;
import vn.signa.sign.internal.review.ReviewViews.LatLng;
import vn.signa.sign.internal.review.ReviewViews.NearbySign;
import vn.signa.sign.internal.review.ReviewViews.QueuePage;

class ReviewQueryServiceTest {

  private static final Instant CREATED = Instant.parse("2026-01-15T08:30:12.345Z");
  private static final GeoPoint HERE = new GeoPoint(21.0285, 105.8048);

  private final ReviewQueryRepository repository = mock(ReviewQueryRepository.class);
  private final SignCatalog catalog = mock(SignCatalog.class);
  private final ObjectStorage storage = mock(ObjectStorage.class);
  private final ReviewQueryService service = new ReviewQueryService(repository, catalog, storage);

  @Test
  void queueAddsSignNamesLookingUpEachCodeOnce() {
    QueueQuery query = ReviewQueries.parse(null, null, null, null, "1", "2");
    when(repository.queue(query)).thenReturn(List.of(row("a", null), row("b", null)));
    when(repository.count(query)).thenReturn(7L);
    when(catalog.findType("P.123a")).thenReturn(Optional.of(type()));

    QueuePage page = service.queue(query);

    assertThat(page.page()).isEqualTo(1);
    assertThat(page.size()).isEqualTo(2);
    assertThat(page.total()).isEqualTo(7);
    assertThat(page.items()).extracting(i -> i.signName()).containsOnly("Cấm rẽ trái");
    assertThat(page.items().getFirst().location()).isEqualTo(new LatLng(21.0285, 105.8048));
    assertThat(page.items().getFirst().confidence()).isEqualTo(0.82);
    assertThat(page.items().getFirst().createdAt()).isEqualTo(CREATED);
    verify(catalog, times(1)).findType("P.123a");
  }

  @Test
  void detailSignsCropUrlsAndListsNearbySigns() {
    when(repository.find("a")).thenReturn(Optional.of(row("a", "cover.jpg")));
    Map<String, BigDecimal> weights = new LinkedHashMap<>();
    weights.put("P.123a", new BigDecimal("1.8000"));
    weights.put("P.123b", new BigDecimal("0.0000"));
    when(repository.codeWeights("a")).thenReturn(weights);
    when(repository.submissions("a"))
        .thenReturn(
            List.of(
                new SubmissionRow(
                    "s1",
                    "P.123a",
                    new BigDecimal("0.9000"),
                    new BigDecimal("0.9000"),
                    "CAMERA",
                    List.of(),
                    92,
                    HERE,
                    CREATED,
                    "GROUPED",
                    "s1.jpg"),
                new SubmissionRow(
                    "s2",
                    "P.123b",
                    new BigDecimal("0.7000"),
                    null,
                    "PHOTO",
                    List.of("USER_EDITED"),
                    null,
                    HERE,
                    CREATED,
                    "RECEIVED",
                    "s2.jpg")));
    when(repository.nearby(eq("a"), eq(HERE), eq(50.0), eq(50)))
        .thenReturn(List.of(new NearbyRow("b", "P.123b", HERE, "APPROVED")));
    when(storage.presignedGet(
            eq(Bucket.SIGN_CROPS), anyString(), eq(ReviewQueryService.CROP_URL_TTL)))
        .thenAnswer(call -> "https://minio/" + call.getArgument(1));

    Detail detail = service.detail("a").orElseThrow();

    assertThat(detail.version()).isEqualTo(7);
    assertThat(detail.coverCropUrl()).isEqualTo("https://minio/cover.jpg");
    assertThat(detail.codeWeights())
        .containsExactly(Map.entry("P.123a", 1.8), Map.entry("P.123b", 0.0));
    assertThat(detail.submissions())
        .extracting(s -> s.cropUrl())
        .containsExactly("https://minio/s1.jpg", "https://minio/s2.jpg");
    assertThat(detail.submissions().get(1).weight()).isNull();
    assertThat(detail.nearbySigns())
        .containsExactly(new NearbySign("b", "P.123b", new LatLng(21.0285, 105.8048), "APPROVED"));
    assertThat(detail.signName()).isNull();
  }

  @Test
  void detailWithoutCoverHasNoUrl() {
    when(repository.find("a")).thenReturn(Optional.of(row("a", null)));
    when(repository.codeWeights("a")).thenReturn(Map.of());

    assertThat(service.detail("a").orElseThrow().coverCropUrl()).isNull();
    assertThat(service.detail("missing")).isEmpty();
  }

  private static SignRow row(String id, String cover) {
    return new SignRow(
        id,
        "P.123a",
        "PROHIBITORY",
        HERE,
        92,
        "PENDING",
        new BigDecimal("0.8200"),
        "APPROVE",
        2,
        List.of(),
        CREATED,
        7,
        false,
        null,
        cover,
        0,
        0);
  }

  private static SignTypeView type() {
    return new SignTypeView("P.123a", "PROHIBITORY", "Cấm rẽ trái", null, null, null, 1, 1, true);
  }
}
