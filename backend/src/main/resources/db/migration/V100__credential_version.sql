ALTER TABLE users
    ADD COLUMN credential_version INT NOT NULL DEFAULT 0,
    ADD COLUMN retained_session_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
