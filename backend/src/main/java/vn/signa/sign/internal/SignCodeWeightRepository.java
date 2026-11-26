package vn.signa.sign.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.signa.sign.clustering.CodeWeight;
import vn.signa.sign.clustering.SignState;

@Repository
class SignCodeWeightRepository {

  private final JdbcClient jdbc;

  SignCodeWeightRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Map<String, Map<String, CodeWeight>> forSigns(List<String> ids) {
    Map<String, Map<String, CodeWeight>> weights = new HashMap<>();
    jdbc.sql(
            """
            SELECT sign_id, sign_type_code, weight, submitter_count
            FROM sign_code_weights WHERE sign_id IN (:ids)
            """)
        .param("ids", ids)
        .query(rs -> {
          while (rs.next()) {
            weights
                .computeIfAbsent(rs.getString("sign_id"), id -> new HashMap<>())
                .put(
                    rs.getString("sign_type_code"),
                    new CodeWeight(rs.getBigDecimal("weight"), rs.getInt("submitter_count")));
          }
          return weights;
        });
    return weights;
  }

  void upsert(SignState sign) {
    sign.codeWeights()
        .forEach((code, weight) ->
            jdbc.sql(
                    """
                    INSERT INTO sign_code_weights (sign_id, sign_type_code, weight, submitter_count)
                    VALUES (:sign, :code, :weight, :submitters) AS incoming
                    ON DUPLICATE KEY UPDATE weight = incoming.weight, submitter_count = incoming.submitter_count
                    """)
                .param("sign", sign.id())
                .param("code", code)
                .param("weight", weight.weight())
                .param("submitters", weight.submitterCount())
                .update());
  }
}
