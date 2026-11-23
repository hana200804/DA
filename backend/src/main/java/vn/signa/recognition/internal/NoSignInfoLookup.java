package vn.signa.recognition.internal;

import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bản tạm khi chưa có danh mục (lát 2): không tra được loại biển nào. */
@Component
class NoSignInfoLookup implements SignInfoLookup {

  @Override
  public Map<String, SignInfo> lookup(Collection<String> signTypeCodes) {
    return Map.of();
  }
}
