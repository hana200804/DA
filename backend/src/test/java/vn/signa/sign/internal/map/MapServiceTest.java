package vn.signa.sign.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.signa.shared.storage.Bucket;
import vn.signa.shared.storage.ObjectStorage;
import vn.signa.sign.internal.map.MapResult.LatLng;
import vn.signa.sign.internal.map.MapResult.MapCluster;
import vn.signa.sign.internal.map.MapResult.MapSign;
import vn.signa.sign.internal.map.MapSignRepository.DetailRow;

class MapServiceTest {

  private final MapSignRepository repository = mock(MapSignRepository.class);
  private final ObjectStorage storage = mock(ObjectStorage.class);
  private final MapService service = new MapService(repository, storage);
  private final MapQuery query =
      new MapQuery(new BoundingBox(0, 0, 1, 1), List.of(), List.of(), 10);

  @Test
  void upTo500SignsAreReturnedAsSigns() {
    when(repository.findSigns(query, 501)).thenReturn(signs(500));

    MapResult result = service.find(query);

    assertThat(result)
        .isInstanceOfSatisfying(MapResult.Signs.class, r -> assertThat(r.items()).hasSize(500));
    assertThat(result.mode()).isEqualTo(MapResult.Mode.SIGNS);
    verify(repository, never()).countByCell(any(), anyDouble());
  }

  @Test
  void the501stSignSwitchesToClustersOverTheWholeBox() {
    var clusters = List.of(new MapCluster(new LatLng(0.5, 0.5), 700));
    when(repository.findSigns(query, 501)).thenReturn(signs(501));
    when(repository.countByCell(query, GridCell.sizeDeg(10))).thenReturn(clusters);
    assertThat(GridCell.sizeDeg(10, query.bbox())).isEqualTo(GridCell.sizeDeg(10));

    MapResult result = service.find(query);

    assertThat(result.mode()).isEqualTo(MapResult.Mode.CLUSTERS);
    assertThat(result).isEqualTo(new MapResult.Clusters(clusters));
  }

  @Test
  void detailSignsCoverCropFor10Minutes() {
    when(repository.findPublished("s1")).thenReturn(Optional.of(row("crops/s1.jpg")));
    when(storage.presignedGet(Bucket.SIGN_CROPS, "crops/s1.jpg", Duration.ofMinutes(10)))
        .thenReturn("https://minio/sign-crops/crops/s1.jpg?X-Amz-Signature=x");

    SignDetail detail = service.detail("s1").orElseThrow();

    assertThat(detail.coverCropUrl())
        .isEqualTo("https://minio/sign-crops/crops/s1.jpg?X-Amz-Signature=x");
    assertThat(detail.confirmCount()).isEqualTo(3);
    assertThat(detail.denyCount()).isEqualTo(1);
  }

  @Test
  void detailWithoutCoverCropHasNullUrl() {
    when(repository.findPublished("s1")).thenReturn(Optional.of(row(null)));

    assertThat(service.detail("s1").orElseThrow().coverCropUrl()).isNull();
    verify(storage, never()).presignedGet(any(), any(), any());
  }

  @Test
  void missingOrUnpublishedDetailIsEmpty() {
    when(repository.findPublished("gone")).thenReturn(Optional.empty());

    assertThat(service.detail("gone")).isEmpty();
  }

  private static List<MapSign> signs(int n) {
    MapSign sign =
        new MapSign("id", "P.123a", "PROHIBITORY", new LatLng(0.5, 0.5), null, "APPROVED", 0.9);
    return Collections.nCopies(n, sign);
  }

  private static DetailRow row(String coverKey) {
    return new DetailRow(
        "s1",
        "P.123a",
        "PROHIBITORY",
        new LatLng(21.0285, 105.8048),
        92,
        "APPROVED",
        0.91,
        3,
        1,
        Instant.parse("2026-01-15T08:30:12.345Z"),
        coverKey);
  }
}
