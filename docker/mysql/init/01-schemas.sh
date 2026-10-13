#!/bin/sh
set -eu

# The official image has already created online_exam and the online_exam user.
# These schemas are reserved for disposable automated fixtures. No demo data
# is installed here.
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<'SQL'
CREATE DATABASE IF NOT EXISTS online_exam_it CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS online_exam_e2e CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON online_exam_it.* TO 'online_exam'@'%';
GRANT ALL PRIVILEGES ON online_exam_e2e.* TO 'online_exam'@'%';
SQL
