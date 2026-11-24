package vn.signa.identity.internal;

import java.util.List;

/** Trang người dùng theo quy ước phân trang của docs/07 §1. */
public record UserPage(List<ManagedUser> items, int page, int size, long total) {

  public UserPage {
    items = List.copyOf(items);
  }
}
