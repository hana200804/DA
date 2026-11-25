package vn.signa.sign.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.signa.shared.storage.Bucket;
import vn.signa.shared.storage.ObjectStorage;
import vn.signa.shared.storage.ObjectStorage.StoredObject;
import vn.signa.shared.storage.ObjectStorageException;

@Component
@EnableScheduling
@Profile("!migrate")
@ConditionalOnProperty(
    name = "signa.clustering.crop-cleanup.enabled",
    havingValue = "true",
    matchIfMissing = true)
class CropCleanup {

  private static final Logger log = LoggerFactory.getLogger(CropCleanup.class);
  private static final int BATCH_SIZE = 250;
  private final ObjectStorage storage;
  private final SubmissionRepository submissions;
  private final Clock clock;

  CropCleanup(ObjectStorage storage, SubmissionRepository submissions, Clock clock) {
    this.storage = storage;
    this.submissions = submissions;
    this.clock = clock;
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
  void clean() {
    Instant now = clock.instant();
    Instant orphanBefore = now.minus(Duration.ofHours(1));
    Instant retiredBefore = now.minus(Duration.ofDays(90));
    List<StoredObject> batch = new ArrayList<>(BATCH_SIZE);
    for (StoredObject object : storage.list(Bucket.SIGN_CROPS, "")) {
      batch.add(object);
      if (batch.size() == BATCH_SIZE) {
        cleanBatch(batch, orphanBefore, retiredBefore);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) cleanBatch(batch, orphanBefore, retiredBefore);
  }

  private void cleanBatch(List<StoredObject> batch, Instant orphanBefore, Instant retiredBefore) {
    var references =
        submissions.cropReferences(batch.stream().map(StoredObject::key).toList(), retiredBefore);
    for (StoredObject object : batch) {
      boolean expired =
          references.getOrDefault(object.key(), object.lastModified().isBefore(orphanBefore));
      if (expired) {
        try {
          storage.delete(Bucket.SIGN_CROPS, object.key());
        } catch (ObjectStorageException e) {
          log.warn("Không xoá được ảnh hết hạn {}", object.key(), e);
        }
      }
    }
  }
}
