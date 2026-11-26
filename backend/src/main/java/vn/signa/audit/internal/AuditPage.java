package vn.signa.audit.internal;

import java.util.List;

/** Trang kết quả theo quy ước phân trang của docs/07 §1. */
public record AuditPage<T>(List<T> items, int page, int size, long total) {

  public AuditPage {
    items = List.copyOf(items);
  }
}
