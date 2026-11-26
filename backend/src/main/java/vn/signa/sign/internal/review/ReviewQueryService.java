package vn.signa.sign.internal.review;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.signa.catalog.api.SignCatalog;
import vn.signa.catalog.api.SignTypeView;
import vn.signa.shared.storage.Bucket;
import vn.signa.shared.storage.ObjectStorage;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.internal.review.ReviewQueries.QueueQuery;
import vn.signa.sign.internal.review.ReviewQueryRepository.SignRow;
import vn.signa.sign.internal.review.ReviewQueryRepository.SubmissionRow;
import vn.signa.sign.internal.review.ReviewViews.Detail;
import vn.signa.sign.internal.review.ReviewViews.LatLng;
import vn.signa.sign.internal.review.ReviewViews.NearbySign;
import vn.signa.sign.internal.review.ReviewViews.QueueItem;
import vn.signa.sign.internal.review.ReviewViews.QueuePage;
import vn.signa.sign.internal.review.ReviewViews.SubmissionView;

/** Hàng đợi phê duyệt và chi tiết biển (FR-21, FR-31). */
@Service
public class ReviewQueryService {

  static final Duration CROP_URL_TTL = Duration.ofMinutes(10);

  /** Bán kính tìm biển đã công bố gần biển đang xem (docs/07 §8). */
  static final double NEARBY_RADIUS_M = 50;

  static final int NEARBY_LIMIT = 50;

  private final ReviewQueryRepository repository;
  private final SignCatalog catalog;
  private final ObjectStorage storage;
  private final TransactionTemplate snapshotTx;

  ReviewQueryService(
      ReviewQueryRepository repository,
      SignCatalog catalog,
      ObjectStorage storage,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.catalog = catalog;
    this.storage = storage;
    this.snapshotTx = new TransactionTemplate(transactionManager);
    this.snapshotTx.setReadOnly(true);
    this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.snapshotTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public QueuePage queue(QueueQuery query) {
    return snapshotTx.execute(status -> readQueue(query));
  }

  private QueuePage readQueue(QueueQuery query) {
    Map<String, String> names = new HashMap<>();
    List<QueueItem> items =
        repository.queue(query).stream()
            .map(row ->
                new QueueItem(
                    row.id(),
                    row.signTypeCode(),
                    names.computeIfAbsent(row.signTypeCode(), this::name),
                    row.categoryCode(),
                    latLng(row.location()),
                    row.headingDeg(),
                    row.status(),
                    row.confidence().doubleValue(),
                    row.suggestion(),
                    row.distinctSubmitters(),
                    row.flags(),
                    row.createdAt()))
            .toList();
    return new QueuePage(items, query.page(), query.size(), repository.count(query));
  }

  public Optional<Detail> detail(String id) {
    Optional<DetailSnapshot> snapshot =
        snapshotTx.execute(status -> repository.find(id).map(this::readDetail));
    return snapshot.map(this::detail);
  }

  private DetailSnapshot readDetail(SignRow row) {
    String signName = name(row.signTypeCode());
    Map<String, Double> weights = new LinkedHashMap<>();
    repository.codeWeights(row.id()).forEach((code, w) -> weights.put(code, w.doubleValue()));
    List<SubmissionRow> submissions = repository.submissions(row.id());
    List<NearbySign> nearby =
        repository.nearby(row.id(), row.location(), NEARBY_RADIUS_M, NEARBY_LIMIT).stream()
            .map(n -> new NearbySign(n.id(), n.signTypeCode(), latLng(n.location()), n.status()))
            .toList();
    return new DetailSnapshot(row, signName, weights, submissions, nearby);
  }

  private Detail detail(DetailSnapshot snapshot) {
    SignRow row = snapshot.sign();
    return new Detail(
        row.id(),
        row.signTypeCode(),
        snapshot.signName(),
        row.categoryCode(),
        latLng(row.location()),
        row.headingDeg(),
        row.status(),
        row.confidence().doubleValue(),
        row.suggestion(),
        row.distinctSubmitters(),
        row.flags(),
        row.createdAt(),
        row.version(),
        row.locationLocked(),
        row.relatedSignId(),
        cropUrl(row.coverCropKey()),
        row.confirmCount(),
        row.denyCount(),
        snapshot.weights(),
        snapshot.submissions().stream().map(this::submission).toList(),
        snapshot.nearby());
  }

  private record DetailSnapshot(
      SignRow sign,
      String signName,
      Map<String, Double> weights,
      List<SubmissionRow> submissions,
      List<NearbySign> nearby) {}

  private SubmissionView submission(SubmissionRow row) {
    BigDecimal weight = row.weight();
    return new SubmissionView(
        row.id(),
        row.signTypeCode(),
        row.confidence().doubleValue(),
        weight == null ? null : weight.doubleValue(),
        row.source(),
        row.flags(),
        row.headingDeg(),
        latLng(row.location()),
        row.receivedAt(),
        row.status(),
        cropUrl(row.cropKey()));
  }

  private String name(String code) {
    return catalog.findType(code).map(SignTypeView::name).orElse(null);
  }

  private String cropUrl(String key) {
    return key == null ? null : storage.presignedGet(Bucket.SIGN_CROPS, key, CROP_URL_TTL);
  }

  private static LatLng latLng(GeoPoint point) {
    return new LatLng(point.lat(), point.lng());
  }
}
