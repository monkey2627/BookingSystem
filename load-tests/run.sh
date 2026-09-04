#!/usr/bin/env bash
# 压测批量执行脚本
# 用法：
#   ./run.sh seed     # 清库并准备测试数据（需先设置 DB_PASS 或配置 ~/.my.cnf）
#   ./run.sh smoke    # 冒烟测试
#   ./run.sh load     # 基准负载
#   ./run.sh stress   # 压力测试
#   ./run.sh spike    # 尖峰（抢档期）
#   ./run.sh soak     # 浸泡测试（1小时）
#   ./run.sh all      # seed 后依次执行 smoke→load→stress→spike
#
# 环境变量：
#   DB_USER   数据库用户名（默认 root）
#   DB_PASS   数据库密码（未设置则依赖 ~/.my.cnf）
#   DB_NAME   数据库名称（默认 mhp）
#   INFLUX_URL  InfluxDB 写入地址（默认 http://localhost:8086/k6）

set -euo pipefail

INFLUX_URL="${INFLUX_URL:-http://localhost:8086/k6}"
RESULTS_DIR="$(dirname "$0")/results"
mkdir -p "$RESULTS_DIR"

DB_USER="${DB_USER:-root}"
DB_NAME="${DB_NAME:-mhp}"
if [[ -n "${DB_PASS:-}" ]]; then
  MYSQL="mysql -u$DB_USER -p$DB_PASS $DB_NAME"
else
  MYSQL="mysql -u$DB_USER $DB_NAME"
fi

run_k6() {
  local name=$1
  local file=$2
  echo "==> [$name] 开始: $(date '+%H:%M:%S')"
  k6 run \
    --out "influxdb=${INFLUX_URL}" \
    --summary-export "${RESULTS_DIR}/${name}_summary.json" \
    "$file"
  echo "==> [$name] 完成: $(date '+%H:%M:%S')"
}

seed_data() {
  echo "==> [seed] 清除旧测试数据..."
  $MYSQL <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
DELETE b FROM booking b JOIN user u ON b.user_id = u.id WHERE u.phone LIKE '199%';
DELETE b FROM booking b JOIN merchant m ON b.merchant_id = m.id JOIN user u ON m.user_id = u.id WHERE u.phone LIKE '199%';
DELETE rr FROM rush_record rr JOIN user u ON rr.user_id = u.id WHERE u.phone LIKE '199%';
DELETE s FROM schedule s JOIN merchant m ON s.merchant_id = m.id JOIN user u ON m.user_id = u.id WHERE u.phone LIKE '199%';
DELETE m FROM merchant m JOIN user u ON m.user_id = u.id WHERE u.phone LIKE '199%';
DELETE FROM user WHERE phone LIKE '199%';
SET FOREIGN_KEY_CHECKS = 1;
SQL

  echo "==> [seed] 注册测试用户..."
  k6 run data/01_seed_users.js

  echo "==> [seed] 创建商家身份..."
  $MYSQL < data/02_seed_merchants.sql

  echo "==> [seed] 创建测试档期..."
  k6 run data/03_seed_schedules.js

  echo "==> [seed] 数据准备完毕"
}

cd "$(dirname "$0")"

case "${1:-}" in
  seed)   seed_data ;;
  smoke)  run_k6 smoke  scenarios/01_smoke.js ;;
  load)   run_k6 load   scenarios/02_load.js ;;
  stress) run_k6 stress scenarios/03_stress.js ;;
  spike)  run_k6 spike  scenarios/04_spike_rush.js ;;
  soak)   run_k6 soak   scenarios/05_soak.js ;;
  all)
    run_k6 smoke  scenarios/01_smoke.js
    run_k6 load   scenarios/02_load.js
    run_k6 stress scenarios/03_stress.js
    run_k6 spike  scenarios/04_spike_rush.js
    echo "==> [all] 全部测试完成，报告在 ${RESULTS_DIR}/"
    ;;
  *)
    echo "用法: $0 {seed|smoke|load|stress|spike|soak|all}"
    exit 1
    ;;
esac
