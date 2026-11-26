package vn.signa.sign.internal.map;

import java.util.List;

/** Kết quả {@code GET /signs}: từng biển, hoặc điểm gộp theo ô lưới khi quá 500 biển. */
public sealed interface MapResult {

  Mode mode();

  enum Mode {
    SIGNS,
    CLUSTERS
  }

  record LatLng(double lat, double lng) {}

  record MapSign(
      String id,
      String signTypeCode,
      String categoryCode,
      LatLng location,
      Integer headingDeg,
      String status,
      double confidence) {}

  record MapCluster(LatLng location, long count) {}

  record Signs(Mode mode, List<MapSign> items) implements MapResult {
    public Signs(List<MapSign> items) {
      this(Mode.SIGNS, List.copyOf(items));
    }
  }

  record Clusters(Mode mode, List<MapCluster> items) implements MapResult {
    public Clusters(List<MapCluster> items) {
      this(Mode.CLUSTERS, List.copyOf(items));
    }
  }
}
