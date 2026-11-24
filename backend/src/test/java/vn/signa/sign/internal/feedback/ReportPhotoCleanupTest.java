package vn.signa.sign.internal.feedback;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static vn.signa.shared.storage.Bucket.REPORT_PHOTOS;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.signa.shared.storage.ObjectStorage;
import vn.signa.shared.storage.ObjectStorage.StoredObject;

class ReportPhotoCleanupTest {

  @Test
  void deletesOnlyExpiredResolvedReportsAndOldOrphans() {
    ObjectStorage storage = mock(ObjectStorage.class);
    ReportRepository reports = mock(ReportRepository.class);
    Instant now = Instant.parse("2026-11-24T03:15:00Z");
    Instant old = now.minus(Duration.ofDays(100));
    var objects =
        List.of(
            new StoredObject("expired.png", old),
            new StoredObject("pending.jpg", old),
            new StoredObject("recently-resolved.webp", old),
            new StoredObject("orphan.jpg", old),
            new StoredObject("upload.jpg", now));
    when(storage.list(REPORT_PHOTOS, "")).thenReturn(objects);
    when(reports.photoReferences(
            objects.stream().map(StoredObject::key).toList(), now.minus(Duration.ofDays(90))))
        .thenReturn(
            Map.of("expired.png", true, "pending.jpg", false, "recently-resolved.webp", false));

    new ReportPhotoCleanup(storage, reports, Clock.fixed(now, ZoneOffset.UTC)).clean();

    verify(storage).list(REPORT_PHOTOS, "");
    verify(storage).delete(REPORT_PHOTOS, "expired.png");
    verify(storage).delete(REPORT_PHOTOS, "orphan.jpg");
    verifyNoMoreInteractions(storage);
  }
}
