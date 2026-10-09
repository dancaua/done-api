CREATE DATABASE done_db DEFAULT CHARACTER SET utf8 DEFAULT COLLATE utf8_general_ci;

CREATE USER 'done_admin'@'localhost' IDENTIFIED BY 'done_pass';
GRANT ALL PRIVILEGES ON done_db.* TO 'done_admin'@'localhost'  WITH GRANT OPTION;
FLUSH PRIVILEGES;