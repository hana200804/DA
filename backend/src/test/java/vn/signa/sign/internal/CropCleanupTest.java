package vn.signa.sign.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static vn.signa.shared.storage.Bucket.SIGN_CROPS;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.signa.shared.storage.ObjectStorage;
import vn.signa.shared.storage.ObjectStorage.StoredObject;

class CropCleanupTest {

  @Test
  void deletesOnlyExpiredRejectionsAndOrphansOlderThanAnHour() {
    ObjectStorage storage = mock(ObjectStorage.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    Instant now = Instant.parse("2026-11-24T03:00:00Z");
    Instant oneHourAgo = now.minus(Duration.ofHours(1));
    var objects =
        List.of(
            new StoredObject("expired.jpg", now.minus(Duration.ofDays(91))),
            new StoredObject("kept.jpg", now.minus(Duration.ofDays(91))),
            new StoredObject("orphan.jpg", oneHourAgo.minusSeconds(1)),
            new StoredObject("recent.jpg", oneHourAgo));
    when(storage.list(SIGN_CROPS, "")).thenReturn(objects);
    when(submissions.cropReferences(
            objects.stream().map(StoredObject::key).toList(), now.minus(Duration.ofDays(90))))
        .thenReturn(Map.of("expired.jpg", true, "kept.jpg", false));

    new CropCleanup(storage, submissions, Clock.fixed(now, ZoneOffset.UTC)).clean();

    verify(storage).list(SIGN_CROPS, "");
    verify(storage).delete(SIGN_CROPS, "expired.jpg");
    verify(storage).delete(SIGN_CROPS, "orphan.jpg");
    verifyNoMoreInteractions(storage);
  }
}
