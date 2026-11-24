package vn.signa.sign.internal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.sign.clustering.SubmissionStatus;
import vn.signa.sign.internal.SubmissionService.Item;
import vn.signa.sign.internal.SubmissionService.Receipt;

@Repository
class SubmissionRepository {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  SubmissionRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  Optional<Receipt> find(String user, String clientId) {
    return jdbc.sql(
            "SELECT id, status FROM submissions WHERE user_id = :user AND client_id = :clientId")
        .param("user", user)
        .param("clientId", clientId)
        .query((rs, row) ->
            new Receipt(rs.getString("id"), SubmissionStatus.valueOf(rs.getString("status"))))
        .optional();
  }

  void insert(
      String id,
      String user,
      String code,
      SubmissionMetadata metadata,
      String cropKey,
      Instant receivedAt) {
    jdbc.sql(
            """
            INSERT INTO submissions
              (id, user_id, client_id, sign_type_code, confidence, location, heading_deg, source, flags,
               crop_key, model_name, model_version, label_map_version, captured_at, received_at, status)
            VALUES (:id, :user, :clientId, :code, :confidence,
                    ST_GeomFromText(:location, 4326, 'axis-order=long-lat'),
                    :heading, :source, :flags, :crop, :name, :version, :labelVersion, :captured, :received,
                    'RECEIVED')
            """)
        .param("id", id)
        .param("user", user)
        .param("clientId", metadata.clientId().toString())
        .param("code", code)
        .param("confidence", metadata.confidence())
        .param("location", SignSql.point(metadata.location()))
        .param("heading", metadata.headingDeg())
        .param("source", metadata.source().name())
        .param("flags", SignSql.flags(json, metadata.flags()))
        .param("crop", cropKey)
        .param("name", metadata.modelName())
        .param("version", metadata.modelVersion())
        .param("labelVersion", metadata.labelMapVersion())
        .param("captured", SignSql.utc(metadata.capturedAt().truncatedTo(ChronoUnit.MILLIS)))
        .param("received", SignSql.utc(receivedAt))
        .update();
  }

  List<Item> items(String user, int page, int size) {
    return jdbc.sql(
            """
            SELECT id, sign_type_code, status, reject_reason, sign_id, received_at, captured_at,
                   ST_Latitude(location) AS lat, ST_Longitude(location) AS lng
            FROM submissions WHERE user_id = :user
            ORDER BY received_at DESC, id DESC LIMIT :size OFFSET :offset
            """)
        .param("user", user)
        .param("size", size)
        .param("offset", (long) page * size)
        .query((rs, row) ->
            new Item(
                rs.getString("id"),
                rs.getString("sign_type_code"),
                SubmissionStatus.valueOf(rs.getString("status")),
                rs.getString("reject_reason"),
                rs.getString("sign_id"),
                SignSql.location(rs),
                SignSql.instant(rs, "received_at"),
                SignSql.instant(rs, "captured_at")))
        .list();
  }

  long count(String user) {
    return jdbc.sql("SELECT COUNT(*) FROM submissions WHERE user_id = :user")
        .param("user", user)
        .query(Long.class)
        .single();
  }
}
