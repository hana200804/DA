-- Lược đồ Signa theo docs/06. MySQL 8, InnoDB, utf8mb4_0900_ai_ci, thời gian UTC.

CREATE TABLE roles (
  code        VARCHAR(20)  NOT NULL PRIMARY KEY,
  name        VARCHAR(100) NOT NULL,
  description VARCHAR(500) NULL
);

CREATE TABLE permissions (
  code        VARCHAR(50)  NOT NULL PRIMARY KEY,
  description VARCHAR(500) NULL
);

CREATE TABLE role_permissions (
  role_code       VARCHAR(20) NOT NULL,
  permission_code VARCHAR(50) NOT NULL,
  PRIMARY KEY (role_code, permission_code),
  CONSTRAINT fk_rp_role FOREIGN KEY (role_code) REFERENCES roles (code),
  CONSTRAINT fk_rp_permission FOREIGN KEY (permission_code) REFERENCES permissions (code)
);

CREATE TABLE users (
  id            CHAR(36)                 NOT NULL PRIMARY KEY,
  email         VARCHAR(255)             NOT NULL,
  password_hash VARCHAR(100)             NOT NULL,
  display_name  VARCHAR(100)             NOT NULL,
  role_code     VARCHAR(20)              NOT NULL DEFAULT 'USER',
  status        ENUM ('ACTIVE','LOCKED') NOT NULL DEFAULT 'ACTIVE',
  last_login_at DATETIME(3)              NULL,
  created_at    DATETIME(3)              NOT NULL,
  updated_at    DATETIME(3)              NOT NULL,
  CONSTRAINT uq_users_email UNIQUE (email),
  CONSTRAINT fk_users_role FOREIGN KEY (role_code) REFERENCES roles (code)
);

CREATE TABLE sign_categories (
  code        VARCHAR(20)  NOT NULL PRIMARY KEY,
  name        VARCHAR(100) NOT NULL,
  description TEXT         NULL,
  sort_order  SMALLINT     NOT NULL
);

CREATE TABLE sign_types (
  code              VARCHAR(30)  NOT NULL PRIMARY KEY,
  category_code     VARCHAR(20)  NOT NULL,
  name              VARCHAR(200) NOT NULL,
  description       TEXT         NULL,
  speed_kmh         SMALLINT     NULL,
  icon_key          VARCHAR(255) NULL,
  label_id          SMALLINT     NULL,
  label_map_version INT          NULL,
  active            BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at        DATETIME(3)  NOT NULL,
  updated_at        DATETIME(3)  NOT NULL,
  CONSTRAINT fk_sign_types_category FOREIGN KEY (category_code) REFERENCES sign_categories (code),
  INDEX ix_sign_types_category_active (category_code, active)
);

CREATE TABLE signs (
  id                  CHAR(36)     NOT NULL PRIMARY KEY,
  sign_type_code      VARCHAR(30)  NOT NULL,
  category_code       VARCHAR(20)  NOT NULL,
  location            POINT        NOT NULL SRID 4326,
  heading_deg         SMALLINT     NULL,
  location_locked     BOOLEAN      NOT NULL DEFAULT FALSE,
  status              ENUM ('PENDING','APPROVED','UNDER_REVIEW','REJECTED','REMOVED') NOT NULL,
  confidence          DECIMAL(5, 4) NOT NULL,
  suggestion          ENUM ('APPROVE','INSPECT','WAIT') NULL,
  distinct_submitters INT          NOT NULL DEFAULT 0,
  confirm_count       INT          NOT NULL DEFAULT 0,
  deny_count          INT          NOT NULL DEFAULT 0,
  deny_since_review   INT          NOT NULL DEFAULT 0,
  moderator_boost     DECIMAL(4, 2) NOT NULL DEFAULT 0,
  flags               JSON         NOT NULL DEFAULT (JSON_ARRAY()),
  related_sign_id     CHAR(36)     NULL,
  cover_crop_key      VARCHAR(255) NULL,
  reviewed_by         CHAR(36)     NULL,
  reviewed_at         DATETIME(3)  NULL,
  version             INT          NOT NULL DEFAULT 0,
  created_at          DATETIME(3)  NOT NULL,
  updated_at          DATETIME(3)  NOT NULL,
  CONSTRAINT fk_signs_type FOREIGN KEY (sign_type_code) REFERENCES sign_types (code),
  CONSTRAINT fk_signs_related FOREIGN KEY (related_sign_id) REFERENCES signs (id),
  CONSTRAINT fk_signs_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id),
  CONSTRAINT ck_signs_heading CHECK (heading_deg IS NULL OR heading_deg BETWEEN 0 AND 359),
  SPATIAL INDEX sx_signs_location (location),
  INDEX ix_signs_queue (status, suggestion, confidence),
  INDEX ix_signs_category_status (category_code, status)
);

CREATE TABLE sign_code_weights (
  sign_id         CHAR(36)       NOT NULL,
  sign_type_code  VARCHAR(30)    NOT NULL,
  weight          DECIMAL(10, 4) NOT NULL,
  submitter_count INT            NOT NULL,
  PRIMARY KEY (sign_id, sign_type_code),
  CONSTRAINT fk_scw_sign FOREIGN KEY (sign_id) REFERENCES signs (id),
  CONSTRAINT fk_scw_type FOREIGN KEY (sign_type_code) REFERENCES sign_types (code)
);

CREATE TABLE submissions (
  id                CHAR(36)      NOT NULL PRIMARY KEY,
  user_id           CHAR(36)      NOT NULL,
  client_id         CHAR(36)      NOT NULL,
  sign_id           CHAR(36)      NULL,
  sign_type_code    VARCHAR(30)   NOT NULL,
  confidence        DECIMAL(5, 4) NOT NULL,
  weight            DECIMAL(6, 4) NULL,
  location          POINT         NOT NULL SRID 4326,
  heading_deg       SMALLINT      NULL,
  source            ENUM ('CAMERA','PHOTO') NOT NULL,
  flags             JSON          NOT NULL DEFAULT (JSON_ARRAY()),
  crop_key          VARCHAR(255)  NOT NULL,
  model_name        VARCHAR(50)   NOT NULL,
  model_version     VARCHAR(50)   NOT NULL,
  label_map_version INT           NOT NULL,
  captured_at       DATETIME(3)   NOT NULL,
  received_at       DATETIME(3)   NOT NULL,
  status            ENUM ('RECEIVED','GROUPED','ACCEPTED','REJECTED') NOT NULL,
  reject_reason     VARCHAR(500)  NULL,
  CONSTRAINT uq_submissions_client UNIQUE (user_id, client_id),
  CONSTRAINT fk_submissions_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_submissions_sign FOREIGN KEY (sign_id) REFERENCES signs (id),
  CONSTRAINT fk_submissions_type FOREIGN KEY (sign_type_code) REFERENCES sign_types (code),
  CONSTRAINT ck_submissions_heading CHECK (heading_deg IS NULL OR heading_deg BETWEEN 0 AND 359),
  INDEX ix_submissions_dedupe (sign_id, user_id, received_at),
  INDEX ix_submissions_user (user_id, received_at),
  INDEX ix_submissions_status (status)
);

CREATE TABLE sign_ratings (
  sign_id    CHAR(36)                 NOT NULL,
  user_id    CHAR(36)                 NOT NULL,
  value      ENUM ('CONFIRM','DENY')  NOT NULL,
  created_at DATETIME(3)              NOT NULL,
  updated_at DATETIME(3)              NOT NULL,
  PRIMARY KEY (sign_id, user_id),
  CONSTRAINT fk_ratings_sign FOREIGN KEY (sign_id) REFERENCES signs (id),
  CONSTRAINT fk_ratings_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE sign_reports (
  id              CHAR(36)      NOT NULL PRIMARY KEY,
  sign_id         CHAR(36)      NOT NULL,
  user_id         CHAR(36)      NOT NULL,
  reason          ENUM ('NOT_EXIST','WRONG_TYPE','WRONG_LOCATION','OTHER') NOT NULL,
  description     VARCHAR(1000) NULL,
  photo_key       VARCHAR(255)  NULL,
  status          ENUM ('PENDING','ACCEPTED','DISMISSED') NOT NULL,
  resolution      ENUM ('EDITED','REMOVED') NULL,
  resolution_note VARCHAR(1000) NULL,
  resolved_by     CHAR(36)      NULL,
  resolved_at     DATETIME(3)   NULL,
  created_at      DATETIME(3)   NOT NULL,
  pending_flag    TINYINT GENERATED ALWAYS AS (IF(status = 'PENDING', 1, NULL)) STORED,
  CONSTRAINT uq_reports_pending UNIQUE (sign_id, user_id, pending_flag),
  CONSTRAINT fk_reports_sign FOREIGN KEY (sign_id) REFERENCES signs (id),
  CONSTRAINT fk_reports_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_reports_resolver FOREIGN KEY (resolved_by) REFERENCES users (id),
  INDEX ix_reports_status (status, created_at)
);

CREATE TABLE audit_logs (
  id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  entity_type  ENUM ('SIGN','REPORT','SIGN_TYPE','SIGN_CATEGORY','USER') NOT NULL,
  entity_id    VARCHAR(36)   NOT NULL,
  action       VARCHAR(40)   NOT NULL,
  actor_type   ENUM ('USER','SYSTEM') NOT NULL,
  actor_id     CHAR(36)      NULL,
  before_value JSON          NULL,
  after_value  JSON          NULL,
  reason       VARCHAR(1000) NULL,
  request_id   VARCHAR(64)   NULL,
  created_at   DATETIME(3)   NOT NULL,
  CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES users (id),
  INDEX ix_audit_entity (entity_type, entity_id, created_at),
  INDEX ix_audit_actor (actor_id, created_at),
  INDEX ix_audit_action (action, created_at)
);
