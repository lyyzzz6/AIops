USE netdata_ops;

ALTER TABLE user_role ADD COLUMN granted_by BIGINT;
ALTER TABLE user_role ADD COLUMN granted_at DATETIME;
ALTER TABLE user_role ADD COLUMN expires_at DATETIME;
