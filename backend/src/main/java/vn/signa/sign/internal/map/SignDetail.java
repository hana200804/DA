package vn.signa.sign.internal.map;

import java.time.Instant;
import vn.signa.sign.internal.map.MapResult.LatLng;

/**
 * Chi tiết công khai của một biển (FR-07). Tên, ý nghĩa, ảnh minh hoạ chuẩn do client lấy từ danh
 * mục theo {@code signTypeCode}. {@code coverCropUrl} là URL ký 10 phút hoặc {@code null}.
 */
public record SignDetail(
    String id,
    String signTypeCode,
    String categoryCode,
    LatLng location,
    Integer headingDeg,
    String status,
    double confidence,
    int confirmCount,
    int denyCount,
    Instant updatedAt,
    String coverCropUrl) {}
