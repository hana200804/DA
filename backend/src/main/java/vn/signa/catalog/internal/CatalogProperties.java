package vn.signa.catalog.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình module catalog.
 *
 * @param importOnStartup nạp {@code signa.labels-path} khi khởi động
 * @param signTypesCacheTtl hạn của nội dung cache theo từng phiên bản danh mục
 */
@ConfigurationProperties("signa.catalog")
public record CatalogProperties(
    boolean importOnStartup, @DefaultValue("24h") Duration signTypesCacheTtl) {}
