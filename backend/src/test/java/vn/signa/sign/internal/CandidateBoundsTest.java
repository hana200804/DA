package vn.signa.sign.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import vn.signa.sign.clustering.Geo;
import vn.signa.sign.clustering.GeoPoint;

class CandidateBoundsTest {

  @Test
  void boxWidensWithLatitudeAndKeepsExactDistanceBoundary() {
    var center = new GeoPoint(60, 105);
    var box = CandidateBounds.around(center, 20).boxes().getFirst();
    assertThat(box.north() - 60).isCloseTo(20 / 111_320.0, within(3e-7));
    assertThat(box.east() - 105).isCloseTo(20 / (111_320 * 0.5), within(5e-7));

    double northAt20M = center.lat() + Math.toDegrees(20 / Geo.EARTH_RADIUS_M);
    assertThat(box.north()).isGreaterThan(northAt20M);
    assertThat(box.west()).isLessThan(104.9997);
    assertThat(box.polygon()).startsWith("POLYGON((" + box.west() + " " + box.south());
  }

  @Test
  void splitsAtDateLineInEitherDirection() {
    for (double lng : new double[] {-179.99995, 179.99995, -180, 180}) {
      var boxes = CandidateBounds.around(new GeoPoint(15, lng), 20).boxes();
      assertThat(boxes).hasSize(2).allMatch(CandidateBounds.Box::atDateline);
      assertThat(boxes.getFirst().east()).isEqualTo(180);
      assertThat(boxes.getLast().west()).isEqualTo(Math.nextUp(-180.0));
      assertThat(boxes.getFirst().west()).isLessThan(179.9999);
      assertThat(boxes.getLast().east()).isGreaterThan(-179.9999);
    }
  }

  @Test
  void latitudeBandCoversAllLongitudesAtEitherPole() {
    var north = CandidateBounds.around(new GeoPoint(89.9999, 33), 20);
    assertThat(north.boxes()).isEmpty();
    assertThat(north.north()).isEqualTo(90);
    var south = CandidateBounds.around(new GeoPoint(-90, 1), 20);
    assertThat(south.boxes()).isEmpty();
    assertThat(south.south()).isEqualTo(-90);
  }
}
