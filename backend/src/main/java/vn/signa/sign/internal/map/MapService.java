package vn.signa.sign.internal.map;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import vn.signa.shared.storage.Bucket;
import vn.signa.shared.storage.ObjectStorage;
import vn.signa.sign.clustering.RatingValue;
import vn.signa.sign.internal.map.MapResult.MapSign;
import vn.signa.sign.internal.map.MapSignRepository.DetailRow;

/** Đọc công khai cho bản đồ (FR-05, FR-06, FR-07). */
@Service
public class MapService {

  /** Quá số này thì trả điểm gộp theo ô lưới (FR-05, docs/06 §9). */
  public static final int MAX_SIGNS = 500;

  /** Hạn URL ký ảnh cắt đại diện (docs/backend/thiet-ke-trien-khai.md §5.1). */
  static final Duration COVER_URL_TTL = Duration.ofMinutes(10);

  private final MapSignRepository repository;
  private final ObjectStorage storage;

  public MapService(MapSignRepository repository, ObjectStorage storage) {
    this.repository = repository;
    this.storage = storage;
  }

  /**
   * Đọc tối đa 501 biển. Được 501 thì gom ô trên toàn vùng bằng truy vấn riêng, để tổng số đếm gồm
   * cả biển nằm ngoài 501 dòng đầu (IT-B07).
   */
  public MapResult find(MapQuery query) {
    List<MapSign> signs = repository.findSigns(query, MAX_SIGNS + 1);
    if (signs.size() <= MAX_SIGNS) {
      return new MapResult.Signs(signs);
    }
    return new MapResult.Clusters(
        repository.countByCell(query, GridCell.sizeDeg(query.zoom(), query.bbox())));
  }

  /** Rỗng khi biển không tồn tại hoặc chưa công bố, đã bị từ chối hay đã gỡ. */
  public Optional<SignDetail> detail(String id) {
    return repository.findPublished(id).map(this::toDetail);
  }

  public Optional<PersonalDetail> detail(String id, String userId) {
    return repository
        .findPublished(id, userId)
        .map(row -> new PersonalDetail(toDetail(row.sign()), row.myRating()));
  }

  public record PersonalDetail(SignDetail sign, RatingValue myRating) {}

  private SignDetail toDetail(DetailRow row) {
    String coverUrl =
        row.coverCropKey() == null
            ? null
            : storage.presignedGet(Bucket.SIGN_CROPS, row.coverCropKey(), COVER_URL_TTL);
    return new SignDetail(
        row.id(),
        row.signTypeCode(),
        row.categoryCode(),
        row.location(),
        row.headingDeg(),
        row.status(),
        row.confidence(),
        row.confirmCount(),
        row.denyCount(),
        row.updatedAt(),
        coverUrl);
  }
}
