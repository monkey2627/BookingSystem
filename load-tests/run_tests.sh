#!/bin/bash
# 用法: bash run_tests.sh <scenario_script> [k6 额外参数]
# 示例: bash run_tests.sh scenarios/02_load.js
#       bash run_tests.sh scenarios/04_spike_rush.js --out json=result.json
#
# 执行顺序: 清库 → 播种用户 → 播种档期 → 压测
# 环境变量:
#   DB_USER  (默认 root)
#   DB_PASS  (默认 空，可在 ~/.my.cnf 配置免密)
#   DB_NAME  (默认 mhp)

set -euo pipefail

SCENARIO="${1:-}"
if [[ -z "$SCENARIO" ]]; then
  echo "用法: bash run_tests.sh <scenario_script> [k6 额外参数]"
  echo "示例: bash run_tests.sh scenarios/02_load.js"
  exit 1
fi
shift  # 剩余参数透传给 k6

DB_USER="${DB_USER:-root}"
DB_NAME="${DB_NAME:-mhp}"

# 构造 mysql 命令（有密码时加 -p$DB_PASS，无密码时依赖 ~/.my.cnf）
if [[ -n "${DB_PASS:-}" ]]; then
  MYSQL="mysql -u$DB_USER -p$DB_PASS $DB_NAME"
else
  MYSQL="mysql -u$DB_USER $DB_NAME"
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=========================================="
echo "  压测环境准备  $(date '+%Y-%m-%d %H:%M:%S')"
echo "=========================================="

# ── Step 1: 清除测试数据 ────────────────────────────────────────────────────
echo ""
echo "[1/3] 清除旧测试数据..."
$MYSQL <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
-- 删除测试账号的预约记录（预约方或商家方均清除）
DELETE b FROM booking b
  JOIN user u ON b.user_id = u.id
  WHERE u.phone LIKE '199%';
DELETE b FROM booking b
  JOIN merchant m ON b.merchant_id = m.id
  JOIN user u ON m.user_id = u.id
  WHERE u.phone LIKE '199%';
-- 删除测试账号的抢档期记录
DELETE rr FROM rush_record rr
  JOIN user u ON rr.user_id = u.id
  WHERE u.phone LIKE '199%';
-- 删除测试商家的档期
DELETE s FROM schedule s
  JOIN merchant m ON s.merchant_id = m.id
  JOIN user u ON m.user_id = u.id
  WHERE u.phone LIKE '199%';
-- 删除测试商家资料
DELETE m FROM merchant m
  JOIN user u ON m.user_id = u.id
  WHERE u.phone LIKE '199%';
-- 删除测试用户
DELETE FROM user WHERE phone LIKE '199%';
SET FOREIGN_KEY_CHECKS = 1;
SQL
echo "  清除完成"

# ── Step 2: 播种用户 ────────────────────────────────────────────────────────
echo ""
echo "[2/3] 播种测试用户..."
cd "$SCRIPT_DIR"
k6 run data/01_seed_users.js 2>&1 | grep -E 'default|WARN|ERR|✓|✗'

# ── Step 3: 播种档期 ────────────────────────────────────────────────────────
echo ""
echo "[3/3] 播种档期..."
k6 run data/03_seed_schedules.js 2>&1 | grep -E 'default|WARN|ERR|✓|✗'

# ── Step 4: 执行压测 ────────────────────────────────────────────────────────
echo ""
echo "=========================================="
echo "  开始压测: $SCENARIO"
echo "=========================================="
k6 run "$SCENARIO" "$@"
