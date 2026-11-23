package vn.signa.sign.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GridCellTest {

  @ParameterizedTest
  @CsvSource({"0, 45", "1, 22.5", "10, 0.0439453125", "14, 0.00274658203125"})
  void cellSizeIs360Over2PowZoomOver8(int zoom, double expected) {
    assertThat(GridCell.sizeDeg(zoom)).isEqualTo(expected);
  }

  @Test
  void cellHalvesWithEachZoomLevel() {
    for (int z = 0; z < 22; z++) {
      assertThat(GridCell.sizeDeg(z + 1)).isEqualTo(GridCell.sizeDeg(z) / 2);
    }
    assertThat(GridCell.sizeDeg(22)).isEqualTo(360.0 / 4_194_304 / 8);
  }

  @Test
  void rejectsZoomOutsideRange() {
    assertThatThrownBy(() -> GridCell.sizeDeg(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GridCell.sizeDeg(23)).isInstanceOf(IllegalArgumentException.class);
  }
}
