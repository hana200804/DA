package vn.signa.sign.internal.places;

import java.util.List;
import vn.signa.sign.internal.map.MapResult.LatLng;

/** Hợp đồng tìm địa điểm để di chuyển bản đồ (FR-14). */
public interface PlaceSearch {

  Result search(String query);

  record Result(List<Place> items) {}

  record Place(String name, LatLng location) {}
}
