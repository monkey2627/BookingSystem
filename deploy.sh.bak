#!/bin/bash
set -e

# ============================================================
# BookingSystem 一键部署脚本
# 用法: ./deploy.sh
# 功能: git pull → 构建后端 → 构建前端 → 重启所有服务
# ============================================================

PROJECT_DIR="/home/ubuntu/BookingSystem"
BACKEND_DIR="$PROJECT_DIR/BookSystem"
FRONTEND_DIR="$PROJECT_DIR/cosplay-frontend"
LOG_DIR="$PROJECT_DIR/logs"
DUBBO_IP="10.2.0.6"

echo "=========================================="
echo "  BookingSystem 一键部署"
echo "  $(date '+%Y-%m-%d %H:%M:%S')"
echo "=========================================="

# 1. 拉取最新代码
echo ""
echo "[1/5] 拉取最新代码..."
cd "$PROJECT_DIR"
git pull

# 2. 构建后端
echo ""
echo "[2/5] 构建后端微服务..."
cd "$BACKEND_DIR"
mvn clean package -DskipTests -q

# 3. 构建前端
echo ""
echo "[3/5] 构建前端..."
cd "$FRONTEND_DIR"
npm install --silent
npm run build

# 4. 停止旧服务 & 清理缓存
echo ""
echo "[4/5] 停止旧服务 & 清理 Dubbo 缓存..."
pkill -f "mhp-account-1.0.0.jar" 2>/dev/null || true
pkill -f "mhp-booking-1.0.0.jar" 2>/dev/null || true
pkill -f "mhp-social-1.0.0.jar" 2>/dev/null || true
pkill -f "mhp-gateway-1.0.0.jar" 2>/dev/null || true
rm -f ~/.dubbo/dubbo-registry-*.cache
sleep 3
echo "  旧服务已停止，缓存已清理"

# 5. 按顺序启动服务
echo ""
echo "[5/5] 启动服务..."
cd "$BACKEND_DIR"
mkdir -p "$LOG_DIR"

# 5.1 启动 account
echo "  启动 mhp-account (8081)..."
DUBBO_IP_TO_REGISTRY=$DUBBO_IP nohup java -Xms128m -Xmx512m -jar mhp-account/target/mhp-account-1.0.0.jar > "$LOG_DIR/account.log" 2>&1 &
echo "    PID: $!"

# 等待 account 初始化
sleep 25

# 5.2 启动 booking + social
echo "  启动 mhp-booking (8082)..."
DUBBO_IP_TO_REGISTRY=$DUBBO_IP nohup java -Xms128m -Xmx512m -jar mhp-booking/target/mhp-booking-1.0.0.jar > "$LOG_DIR/booking.log" 2>&1 &
echo "    PID: $!"

echo "  启动 mhp-social (8083)..."
DUBBO_IP_TO_REGISTRY=$DUBBO_IP nohup java -Xms128m -Xmx512m -jar mhp-social/target/mhp-social-1.0.0.jar > "$LOG_DIR/social.log" 2>&1 &
echo "    PID: $!"

# 等待 booking + social 初始化
sleep 25

# 5.3 启动 gateway
echo "  启动 mhp-gateway (8080)..."
nohup java -Xms128m -Xmx256m -jar mhp-gateway/target/mhp-gateway-1.0.0.jar > "$LOG_DIR/gateway.log" 2>&1 &
echo "    PID: $!"

# 等待 gateway 初始化
sleep 10

# 6. 验证
echo ""
echo "=========================================="
echo "  部署完成！服务状态："
echo "=========================================="
ps aux | grep "mhp-.*-1.0.0.jar" | grep -v grep | awk '{printf "  %s (PID: %s)\n", $NF, $2}' | sed 's|.*/||' | sed 's|-1.0.0.jar||'

echo ""
echo "端口监听："
sudo ss -tlnp | grep -E "808[0-4]" | awk '{printf "  :%s -> PID %s\n", $4, $NF}' | sed 's/.*://' | sed 's/users:(//' | sed 's/,.*//' | sed 's/(//' | sed 's/)//' | sort -t: -k2 -n

echo ""
echo "=========================================="
echo "  访问地址: http://62.234.139.139"
echo "=========================================="
