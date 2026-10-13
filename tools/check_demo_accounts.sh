#!/bin/sh
set -eu
MYSQL_PWD="$MYSQL_PASSWORD" mysql -u online_exam -N -D "${EXAM_DB_NAME:-online_exam}" -e "
SELECT COUNT(*) AS accounts,
       COALESCE(SUM(role = 'INSTRUCTOR'), 0) AS instructors,
       COALESCE(SUM(role = 'STUDENT'), 0) AS students,
       COALESCE(SUM(password_hash LIKE '{bcrypt}%'), 0) AS bcrypt_hashes
FROM users
WHERE username IN ('demo_instructor1', 'demo_instructor2',
                   'demo_student1', 'demo_student2', 'demo_student3');
"
