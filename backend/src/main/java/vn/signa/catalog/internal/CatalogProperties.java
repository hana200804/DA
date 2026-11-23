package vn.signa.catalog.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình module catalog.
 *
 * @param importOnStartup nạp {@code signa.labels-path} khi khởi động
 * @param signTypesCacheTtl hạn của {@code cache:sign-types}; cache vẫn bị xoá ngay khi danh mục
 *     đổi, hạn này chỉ để tự lành nếu lần xoá đó lỗi
 */
@ConfigurationProperties("signa.catalog")
public record CatalogProperties(
    boolean importOnStartup, @DefaultValue("24h") Duration signTypesCacheTtl) {}
