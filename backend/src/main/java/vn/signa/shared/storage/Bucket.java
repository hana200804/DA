package vn.signa.shared.storage;

/** Ba bucket MinIO theo docs/06 §11. */
public enum Bucket {
  /** Ảnh cắt của đề xuất, riêng tư, đọc qua URL ký. */
  SIGN_CROPS("sign-crops", false),
  /** Ảnh đính kèm báo cáo, riêng tư, đọc qua URL ký. */
  REPORT_PHOTOS("report-photos", false),
  /** Ảnh minh hoạ loại biển, đọc công khai qua nginx {@code /icons/}. */
  SIGN_ICONS("sign-icons", true);

  private final String bucketName;
  private final boolean publicRead;

  Bucket(String bucketName, boolean publicRead) {
    this.bucketName = bucketName;
    this.publicRead = publicRead;
  }

  public String bucketName() {
    return bucketName;
  }

  public boolean publicRead() {
    return publicRead;
  }
}
