package vn.signa.sign.internal.places;

import java.util.List;

final class NoopPlaceSearch implements PlaceSearch {

  @Override
  public Result search(String query) {
    return new Result(List.of());
  }
}
