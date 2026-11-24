package vn.signa.catalog.api;

import java.time.Instant;

/** Đổi nhóm loại biển, phát đồng bộ trong giao dịch ghi danh mục. */
public record SignTypeCategoryChanged(
    String signTypeCode, String categoryCode, Instant updatedAt) {}
