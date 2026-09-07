#!/usr/bin/env bash

set -euo pipefail

required_variables=(
  MYSQL_HOST
  MYSQL_PORT
  MYSQL_PWD
  TIDEBID_ACCOUNT_DB_PASSWORD
  TIDEBID_AUCTION_DB_PASSWORD
  TIDEBID_TRADE_DB_PASSWORD
  TIDEBID_AI_DB_PASSWORD
)

for variable_name in "${required_variables[@]}"; do
  variable_value="${!variable_name:-}"
  if [[ -z "${variable_value}" ]]; then
    echo "Required environment variable ${variable_name} is missing." >&2
    exit 64
  fi
  if [[ "${variable_value}" == *$'\n'* || "${variable_value}" == *$'\r'* ]]; then
    echo "Environment variable ${variable_name} must not contain line breaks." >&2
    exit 64
  fi
done

escape_sql_string() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e "s/'/''/g"
}

account_password="$(escape_sql_string "${TIDEBID_ACCOUNT_DB_PASSWORD}")"
auction_password="$(escape_sql_string "${TIDEBID_AUCTION_DB_PASSWORD}")"
trade_password="$(escape_sql_string "${TIDEBID_TRADE_DB_PASSWORD}")"
ai_password="$(escape_sql_string "${TIDEBID_AI_DB_PASSWORD}")"

mysql_args=(
  --protocol=TCP
  --host="${MYSQL_HOST}"
  --port="${MYSQL_PORT}"
  --user=root
  --batch
  --skip-column-names
)

mysql "${mysql_args[@]}" <<SQL
CREATE DATABASE IF NOT EXISTS \`tidebid_account\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS \`tidebid_auction\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS \`tidebid_trade\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS \`tidebid_ai\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

ALTER DATABASE \`tidebid_account\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER DATABASE \`tidebid_auction\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER DATABASE \`tidebid_trade\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER DATABASE \`tidebid_ai\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'tidebid_account_app'@'%' IDENTIFIED BY '${account_password}';
CREATE USER IF NOT EXISTS 'tidebid_auction_app'@'%' IDENTIFIED BY '${auction_password}';
CREATE USER IF NOT EXISTS 'tidebid_trade_app'@'%' IDENTIFIED BY '${trade_password}';
CREATE USER IF NOT EXISTS 'tidebid_ai_app'@'%' IDENTIFIED BY '${ai_password}';

ALTER USER 'tidebid_account_app'@'%' IDENTIFIED BY '${account_password}';
ALTER USER 'tidebid_auction_app'@'%' IDENTIFIED BY '${auction_password}';
ALTER USER 'tidebid_trade_app'@'%' IDENTIFIED BY '${trade_password}';
ALTER USER 'tidebid_ai_app'@'%' IDENTIFIED BY '${ai_password}';

REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'tidebid_account_app'@'%';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'tidebid_auction_app'@'%';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'tidebid_trade_app'@'%';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'tidebid_ai_app'@'%';

GRANT ALL PRIVILEGES ON \`tidebid_account\`.* TO 'tidebid_account_app'@'%';
GRANT ALL PRIVILEGES ON \`tidebid_auction\`.* TO 'tidebid_auction_app'@'%';
GRANT ALL PRIVILEGES ON \`tidebid_trade\`.* TO 'tidebid_trade_app'@'%';
GRANT ALL PRIVILEGES ON \`tidebid_ai\`.* TO 'tidebid_ai_app'@'%';
SQL

verify_account_isolation() {
  local username="$1"
  local password="$2"
  local own_database="$3"
  local denied_database="$4"
  local visible_schema_count
  local account_mysql_args=(
    --protocol=TCP
    --host="${MYSQL_HOST}"
    --port="${MYSQL_PORT}"
    --user="${username}"
    --batch
    --skip-column-names
  )

  MYSQL_PWD="${password}" mysql "${account_mysql_args[@]}" <<SQL
CREATE TABLE IF NOT EXISTS \`${own_database}\`.\`__tidebid_permission_probe\` (\`id\` INT NOT NULL PRIMARY KEY);
DROP TABLE \`${own_database}\`.\`__tidebid_permission_probe\`;
SQL

  if MYSQL_PWD="${password}" mysql "${account_mysql_args[@]}" \
    --execute="CREATE TABLE \`${denied_database}\`.\`__tidebid_forbidden_probe\` (\`id\` INT NOT NULL PRIMARY KEY);" \
    >/dev/null 2>&1; then
    MYSQL_PWD="${password}" mysql "${account_mysql_args[@]}" \
      --execute="DROP TABLE IF EXISTS \`${denied_database}\`.\`__tidebid_forbidden_probe\`;" \
      >/dev/null 2>&1 || true
    echo "Database account ${username} unexpectedly wrote to ${denied_database}." >&2
    exit 65
  fi

  visible_schema_count="$(MYSQL_PWD="${password}" mysql "${account_mysql_args[@]}" \
    --execute="SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME IN ('tidebid_account', 'tidebid_auction', 'tidebid_trade', 'tidebid_ai');")"
  if [[ "${visible_schema_count}" != "1" ]]; then
    echo "Database account ${username} can see ${visible_schema_count} TideBid schemas; expected exactly 1." >&2
    exit 65
  fi

  echo "Verified database isolation for ${username}."
}

verify_account_isolation "tidebid_account_app" "${TIDEBID_ACCOUNT_DB_PASSWORD}" "tidebid_account" "tidebid_auction"
verify_account_isolation "tidebid_auction_app" "${TIDEBID_AUCTION_DB_PASSWORD}" "tidebid_auction" "tidebid_trade"
verify_account_isolation "tidebid_trade_app" "${TIDEBID_TRADE_DB_PASSWORD}" "tidebid_trade" "tidebid_ai"
verify_account_isolation "tidebid_ai_app" "${TIDEBID_AI_DB_PASSWORD}" "tidebid_ai" "tidebid_account"

echo "TideBid database bootstrap completed successfully."
