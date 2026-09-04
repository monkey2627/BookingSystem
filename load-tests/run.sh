#!/usr/bin/env bash
# 压测一键执行脚本
# 用法：
#   ./run.sh smoke    # 冒烟测试
#   ./run.sh load     # 基准负载
#   ./run.sh stress   # 压力测试
#   ./run.sh spike    # 尖峰（抢档期）
#   ./run.sh soak     # 浸泡测试（1小时）
#   ./run.sh all      # 依次执行 smoke→load→stress→spike
#   ./run.sh seed     # 仅准备测试数据

set -euo pipefail

INFLUX_URL="http://localhost:8086/k6"
RESULTS_DIR="$(dirname "$0")/results"
mkdir -p "$RESULTS_DIR"

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
  echo "==> [seed] 注册测试用户..."
  k6 run data/01_seed_users.js

  echo "==> [seed] 创建商家身份..."
  docker exec -i mhp-mysql mysql -uroot -p222333dyh mhp < data/02_seed_merchants.sql

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
