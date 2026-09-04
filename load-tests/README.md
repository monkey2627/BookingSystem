# MHP 压测方案

## 目录结构

```
load-tests/
├── config.js                  # 全局常量（BASE_URL、ResultCode、阈值）
├── run.sh                     # 一键执行脚本
├── helpers/
│   ├── auth.js                # 登录 / Token 池
│   └── checks.js              # 断言封装
├── data/
│   ├── 01_seed_users.js       # 注册 200 个测试用户（k6 脚本）
│   ├── 02_seed_merchants.sql  # 创建商家记录（SQL）
│   └── 03_seed_schedules.js   # 为商家创建档期（k6 脚本）
└── scenarios/
    ├── 01_smoke.js            # 冒烟：2 VU / 1min，验证环境
    ├── 02_load.js             # 基准：0→50→0 VU / 10min，测 p95 RT
    ├── 03_stress.js           # 压力：0→200 VU，找崩溃点
    ├── 04_spike_rush.js       # 尖峰：200 VU 同时抢档期
    └── 05_soak.js             # 浸泡：30 VU / 1h，检测内存泄漏
```

---

## 前置条件

### 1. 安装 k6

```bash
# Windows（推荐用 Scoop）
scoop install k6

# macOS
brew install k6

# 验证
k6 version
```

### 2. 启动监控基础设施

在项目根目录（`E:\dyh\MHP`）执行：

```bash
# 首次启动需要先构建 ES（含 IK 分词器，约 3~5 分钟）
docker compose build elasticsearch

# 启动全部中间件 + 监控组件
docker compose up -d

# 确认 InfluxDB、Prometheus、Grafana 就绪
docker compose ps | grep -E "influxdb|prometheus|grafana"
```

### 3. 配置 Grafana 数据源和看板

访问 http://localhost:3000（admin / admin），首次登录后：

**添加数据源 1 — InfluxDB（k6 压测指标）：**
1. Configuration → Data Sources → Add data source → InfluxDB
2. URL: `http://influxdb:8086`，Database: `k6`，保存
3. Import Dashboard → ID: `2587`，选择刚建的 InfluxDB 数据源

**添加数据源 2 — Prometheus（JVM / Spring Boot 指标）：**
1. Configuration → Data Sources → Add data source → Prometheus
2. URL: `http://prometheus:9090`，保存
3. Import Dashboard → ID: `4701`（JVM），数据源选 Prometheus
4. Import Dashboard → ID: `11378`（Spring Boot），数据源选 Prometheus

### 4. 启动三个后端服务

```bash
cd BookSystem
mvn clean package -DskipTests

java -jar mhp-account/target/mhp-account-1.0.0.jar &
java -jar mhp-booking/target/mhp-booking-1.0.0.jar &
java -jar mhp-social/target/mhp-social-1.0.0.jar &

# 验证 Prometheus 能采集到指标（等服务完全启动后）
curl http://localhost:8081/actuator/prometheus | head -5
curl http://localhost:8082/actuator/prometheus | head -5
curl http://localhost:8083/actuator/prometheus | head -5
```

---

## 执行步骤

### Step 1 — 准备测试数据

```bash
cd load-tests

# 注册 200 个测试用户（约 15s）
k6 run data/01_seed_users.js

# 创建 50 个商家（需 docker 运行）
docker exec -i mhp-mysql mysql -uroot -p222333dyh mhp < data/02_seed_merchants.sql

# 为每个商家创建 14 天 × 2 种档期（约 2min）
k6 run data/03_seed_schedules.js
```

> 数据播种是幂等的，可重复执行。

### Step 2 — 冒烟测试（每次改动后必跑）

```bash
k6 run scenarios/01_smoke.js
```

冒烟通过（无报错）才继续后续测试。

### Step 3 — 基准负载测试

```bash
k6 run --out influxdb=http://localhost:8086/k6 scenarios/02_load.js
```

观察指标：
- **Grafana k6 Dashboard (2587)**：RPS、RT 分位数、错误率
- **Grafana JVM Dashboard (4701)**：Heap、GC 停顿、线程数
- 成功标准：`p95 < 500ms`，`error rate < 1%`

### Step 4 — 压力测试（找崩溃点）

```bash
k6 run --out influxdb=http://localhost:8086/k6 scenarios/03_stress.js
```

观察：RT 曲线急剧上扬或错误率超 5% 时对应的 VU 数，即为当前容量上限。

### Step 5 — 尖峰测试（验证抢档期原子性）

```bash
k6 run --out influxdb=http://localhost:8086/k6 scenarios/04_spike_rush.js
```

测试结束后 teardown 会打印最终排队人数，应 <= 5（maxQueueSize）。

### Step 6 — 浸泡测试（检测内存泄漏，约 1 小时）

```bash
k6 run --out influxdb=http://localhost:8086/k6 scenarios/05_soak.js
```

期间在 Grafana JVM Dashboard 观察：
- `Heap Used` 不应单调递增（GC 应能回收）
- `DB Connections Active` 在稳态后不应持续增长

### 一键执行（冒烟→基准→压力→尖峰）

```bash
chmod +x run.sh
./run.sh all
```

---

## 测试后清理

```bash
# 清理抢档期 Redis ZSET（--scan 避免阻塞 Redis）
redis-cli --scan --pattern "schedule:*" | xargs -r redis-cli DEL

# 清理测试数据（谨慎，会删除所有 test_user_ 相关数据）
docker exec -i mhp-mysql mysql -uroot -p222333dyh mhp -e "
  DELETE FROM booking WHERE user_id IN (SELECT id FROM user WHERE username LIKE 'test_user_%');
  DELETE FROM schedule WHERE merchant_id IN (
    SELECT m.id FROM merchant m JOIN user u ON m.user_id = u.id WHERE u.username LIKE 'test_user_%'
  );
  DELETE FROM merchant WHERE user_id IN (SELECT id FROM user WHERE username LIKE 'test_user_%');
  DELETE FROM user WHERE username LIKE 'test_user_%';
"
```

---

## 成功标准汇总

| 测试类型 | p95 RT  | 错误率 | 关注点 |
|---------|---------|--------|--------|
| 冒烟    | —       | 0%     | 脚本无报错，接口 200 |
| 基准    | < 500ms | < 1%   | 50 VU 下稳定基线 |
| 压力    | —       | < 5%   | 记录崩溃点 VU 数 |
| 尖峰    | < 200ms | < 1%   | 排队人数 <= maxQueueSize |
| 浸泡    | < 500ms | < 1%   | Heap 无单调递增 |
