package vn.signa.sign.internal;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
class SubmissionStream {

  static final String KEY = "stream:submissions";
  static final String QUEUED = "{stream:submissions}:queued";
  static final String GROUP = "clustering";

  private static final DefaultRedisScript<Long> ADD =
      new DefaultRedisScript<>(
          """
          local record = redis.call('HGET', KEYS[2], ARGV[1])
          if record then
            local entry = redis.call('XRANGE', KEYS[1], record, record)[1]
            if entry and entry[2][2] == ARGV[1] then return 0 end
          end
          record = redis.call('XADD', KEYS[1], '*', 'id', ARGV[1])
          redis.call('HSET', KEYS[2], ARGV[1], record)
          return 1
          """,
          Long.class);

  private static final DefaultRedisScript<Long> COMPLETE =
      new DefaultRedisScript<>(
          """
          redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
          redis.call('XDEL', KEYS[1], ARGV[2])
          if ARGV[3] ~= '' and redis.call('HGET', KEYS[2], ARGV[3]) == ARGV[2] then
            redis.call('HDEL', KEYS[2], ARGV[3])
          end
          return 1
          """,
          Long.class);

  private final StringRedisTemplate redis;
  private final StreamOperations<String, String, String> streams;

  SubmissionStream(StringRedisTemplate redis) {
    this.redis = redis;
    this.streams = redis.opsForStream();
  }

  void add(String submissionId) {
    redis.execute(ADD, List.of(KEY, QUEUED), submissionId);
  }

  void createGroup() {
    try {
      redis.execute(
          (RedisCallback<String>)
              connection ->
                  connection
                      .streamCommands()
                      .xGroupCreate(
                          KEY.getBytes(StandardCharsets.UTF_8),
                          GROUP,
                          ReadOffset.from("0-0"),
                          true));
    } catch (RuntimeException e) {
      if (!hasRedisError(e, "BUSYGROUP")) throw e;
    }
  }

  List<MapRecord<String, String, String>> claim(String consumer) {
    while (true) {
      RecordId[] ids =
          streams.pending(KEY, GROUP, Range.unbounded(), 32).stream()
              .map(PendingMessage::getId)
              .toArray(RecordId[]::new);
      if (ids.length == 0) return List.of();
      var claimed = streams.claim(KEY, GROUP, consumer, Duration.ZERO, ids);
      if (!claimed.isEmpty()) return claimed;
      // Redis 7 xoá mục pending không còn nội dung khi XCLAIM. Đọc tiếp nếu cả lô đã bị xoá.
    }
  }

  @SuppressWarnings("unchecked") // StreamOperations nhận varargs StreamOffset<K>.
  List<MapRecord<String, String, String>> read(String consumer) {
    var records =
        streams.read(
            Consumer.from(GROUP, consumer),
            StreamReadOptions.empty().count(32).block(Duration.ofSeconds(1)),
            StreamOffset.create(KEY, ReadOffset.lastConsumed()));
    return records == null ? List.of() : records;
  }

  void complete(RecordId id, String submissionId) {
    redis.execute(
        COMPLETE,
        List.of(KEY, QUEUED),
        GROUP,
        id.getValue(),
        submissionId == null ? "" : submissionId);
  }

  long length() {
    return streams.size(KEY);
  }

  long pending() {
    try {
      return streams.pending(KEY, GROUP).getTotalPendingMessages();
    } catch (RuntimeException e) {
      if (hasRedisError(e, "NOGROUP")) return 0;
      throw e;
    }
  }

  private static boolean hasRedisError(Throwable error, String code) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null && cause.getMessage().startsWith(code + " ")) return true;
    }
    return false;
  }
}
