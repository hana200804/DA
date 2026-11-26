package vn.signa.recognition.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import vn.signa.catalog.api.SignCatalog;
import vn.signa.catalog.api.SignTypeView;

/**
 * Tra tên, ý nghĩa, ảnh minh hoạ qua {@link SignCatalog} của lát 2. Mỗi mã khác nhau tra một lần.
 * Danh mục lỗi thì kết quả nhận diện vẫn trả về, chỉ thiếu ba trường này.
 */
@Component
class CatalogSignInfoLookup implements SignInfoLookup {

  private static final Logger log = LoggerFactory.getLogger(CatalogSignInfoLookup.class);

  private final SignCatalog catalog;

  CatalogSignInfoLookup(SignCatalog catalog) {
    this.catalog = catalog;
  }

  @Override
  public Map<String, SignInfo> lookup(Collection<String> signTypeCodes) {
    Map<String, SignInfo> result = new LinkedHashMap<>();
    try {
      for (String code : new LinkedHashSet<>(signTypeCodes)) {
        catalog.findType(code).ifPresent(type -> result.put(code, info(type)));
      }
    } catch (DataAccessException e) {
      log.warn("Không tra được danh mục biển: {}", e.getClass().getSimpleName());
      return Map.of();
    }
    return result;
  }

  private static SignInfo info(SignTypeView type) {
    return new SignInfo(type.name(), type.description(), type.iconUrl());
  }
}
