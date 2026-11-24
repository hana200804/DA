package vn.signa.sign.api;

import vn.signa.catalog.api.SignTypeCategoryChanged;

/** Đồng bộ nhóm lưu trên các biển trong giao dịch đổi nhóm của danh mục. */
public interface SignCategoryUpdater {

  void updateCategory(SignTypeCategoryChanged change);
}
