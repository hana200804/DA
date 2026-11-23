package vn.signa.sign.internal.map;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BoundingBoxTest {

  @Test
  void smallBoxIsAClosedCounterClockwiseRectangleLongitudeFirst() {
    assertThat(new BoundingBox(105.8, 21.02, 105.81, 21.03).toWkt())
        .isEqualTo("POLYGON((105.8 21.02, 105.81 21.02, 105.81 21.03, 105.8 21.03, 105.8 21.02))");
  }

  @Test
  void numbersAreNeverInScientificNotation() {
    assertThat(new BoundingBox(-0.0001, 0, 0.00000001, 1).toWkt())
        .isEqualTo("POLYGON((-0.0001 0, 0.00000001 0, 0.00000001 1, -0.0001 1, -0.0001 0))");
  }

  @Test
  void wideBoxIsSplitSoNoEdgeExceeds90DegreesOfLongitude() {
    assertThat(new BoundingBox(-180, -85, 180, 85).toWkt())
        .isEqualTo(
            "POLYGON((-180 -85, -90 -85, 0 -85, 90 -85, 180 -85,"
                + " 180 85, 90 85, 0 85, -90 85, -180 85, -180 -85))");
  }
}
