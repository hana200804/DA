-- Phiên bản cache công khai tăng trong cùng giao dịch thay đổi loại biển.
CREATE TABLE catalog_generation (
  id TINYINT PRIMARY KEY,
  generation BIGINT NOT NULL,
  CONSTRAINT ck_catalog_generation_singleton CHECK (id = 1)
);

INSERT INTO catalog_generation (id, generation) VALUES (1, 1);
GRANT SELECT, UPDATE ON catalog_generation TO ${app_user};
