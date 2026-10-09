-- MySQL 8.4+. Local development only; use private credentials for any deployment.
-- The schema uses ownership triggers; only the migration account gets TRIGGER privileges.
-- ROW binary logging remains enabled. Runtime cannot create functions/triggers.
SET PERSIST log_bin_trust_function_creators = ON;
-- Existing database contents and existing account passwords are preserved.
CREATE DATABASE IF NOT EXISTS done_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
CREATE USER IF NOT EXISTS 'done_admin'@'localhost' IDENTIFIED BY 'done_pass';
GRANT ALL PRIVILEGES ON done_db.* TO 'done_admin'@'localhost';
-- No global permissions or GRANT OPTION are required for local development.
