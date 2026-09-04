# MHP 压测方案

## 目录结构

```
load-tests/
├── config.js                  # 全局常量（BASE_URL、ResultCode、阈值）
├── run.sh                     # 多场景批量执行（smoke/load/stress/spike/soak/all）
├── run_tests.sh               # 单场景执行（自动完成清库→播种→压测）
├── helpers/
│   ├── auth.js                # 登录 / Token 池
│   └── checks.js              # 断言封装
├── data/
│   ├── 01_seed_users.js       # 注册 200 个测试用户（k6 脚本）
│   ├── 02_seed_merchants.sql  # 创建商家记录（SQL，用户 1~50 注册为商家）
│   └── 03_seed_schedules.js   # 为商家创建 14 天档期（k6 脚本）
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
# Ubuntu / Debian（服务器环境）
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt update && sudo apt install k6

# macOS
brew install k6

# Windows（Scoop）
scoop install k6

# 验证
k6 version
```

### 2. 启动中间件

在项目根目录执行：

```bash
# 首次启动需先构建含 IK 分词器的 ES 镜像（约 3~5 分钟）
docker compose build elasticsearch

# 启动所有中间件（MySQL / Redis / RabbitMQ / Nacos / XXL-Job / ES 等）
docker compose up -d
```

### 3. 部署后端微服务

使用项目根目录的一键部署脚本：

```bash
cd ~/BookingSystem
bash deploy.sh
```

部署完成后四个微服务应在以下端口就绪：

| 服务 | 端口 | 说明 |
|------|------|------|
| mhp-gateway | 8080 | API 网关，Nginx 将 80 → 8080 |
| mhp-account | 8081 | 用户 / 商家服务 |
| mhp-booking | 8082 | 档期 / 预约服务 |
| mhp-social  | 8083 | 社区 / 消息服务 |

验证：

```bash
# 确认四个服务进程
ps aux | grep 'mhp-.*jar' | grep -v grep | awk '{print $NF}' | sed 's|.*/||'

# 确认端口监听
ss -tlnp | grep -E '808[0-3]'

# 验证登录（需先完成 Step 1 播种）
curl -s -X POST http://localhost:80/api/user/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"19900000001","password":"Test@123456"}'
```

### 4. （可选）配置 Grafana 监控

访问 http://localhost:3000（admin / admin）：

**InfluxDB 数据源（k6 压测指标）：**
1. Configuration → Data Sources → Add → InfluxDB
2. URL: `http://influxdb:8086`，Database: `k6`，保存
3. Import Dashboard → ID `2587`，数据源选 InfluxDB

**Prometheus 数据源（JVM / Spring Boot 指标）：**
1. Configuration → Data Sources → Add → Prometheus
2. URL: `http://prometheus:9090`，保存
3. Import Dashboard → ID `4701`（JVM），数据源选 Prometheus
4. Import Dashboard → ID `11378`（Spring Boot），数据源选 Prometheus

未配置 Grafana 时去掉 `--out influxdb=...` 参数仍可正常压测，只是无实时图表。

---

## 执行步骤

### ⚠️ 关于测试数据

**播种不是幂等的。** 测试用户通过注册接口创建，密码由后端 BCrypt 加密存储。若数据库中已存在相同手机号的账号（来自旧版本或旧密码），注册接口会返回"已存在"跳过，导致登录时密码不匹配。

**每次压测前必须先清库再播种，** 使用 `run_tests.sh` 可自动完成此流程。

---

### 方式一：`run_tests.sh`（推荐，单场景）

自动完成：清库 → 播种用户 → 播种档期 → 执行压测。

```bash
cd load-tests

# 配置数据库密码（或在 ~/.my.cnf 中配置免密）
export DB_PASS=your_mysql_password

# 运行指定场景
bash run_tests.sh scenarios/01_smoke.js
bash run_tests.sh scenarios/02_load.js
bash run_tests.sh scenarios/04_spike_rush.js

# 带 k6 额外参数（如输出到 InfluxDB）
bash run_tests.sh scenarios/02_load.js --out influxdb=http://localhost:8086/k6
```

---

### 方式二：`run.sh`（多场景批量执行）

> ⚠️ 执行 `seed` 子命令前，需手动完成清库（见下方"手动清库"）。

```bash
cd load-tests

# 仅播种（先手动清库）
./run.sh seed

# 单独执行某个场景（需已播种）
./run.sh smoke
./run.sh load
./run.sh stress
./run.sh spike
./run.sh soak

# 依次执行 smoke → load → stress → spike（需已播种）
./run.sh all
```

汇总报告保存在 `results/<name>_summary.json`。

---

### 方式三：手动逐步执行

```bash
cd load-tests

# Step 1：清除旧测试数据
export DB_PASS=your_mysql_password
mysql -uroot -p$DB_PASS mhp <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
DELETE b FROM booking b JOIN user u ON b.user_id = u.id WHERE u.phone LIKE '199%';
DELETE b FROM booking b JOIN merchant m ON b.merchant_id = m.id JOIN user u ON m.user_id = u.id WHERE u.phone LIKE '199%';
DELETE rr FROM rush_record rr JOIN user u ON rr.user_id = u.id WHERE u.phone LIKE '199%';
DELETE s FROM schedule s JOIN merchant m ON s.merchant_id = m.id JOIN user u ON m.user_id = u.id WHERE u.phone LIKE '199%';
DELETE m FROM merchant m JOIN user u ON m.user_id = u.id WHERE u.phone LIKE '199%';
DELETE FROM user WHERE phone LIKE '199%';
SET FOREIGN_KEY_CHECKS = 1;
SQL

# Step 2：注册 200 个测试用户（约 15s）
k6 run data/01_seed_users.js

# Step 3：创建商家身份（用户 1~50 注册为商家）
mysql -uroot -p$DB_PASS mhp < data/02_seed_merchants.sql

# Step 4：为每个商家创建 14 天档期（约 2min）
k6 run data/03_seed_schedules.js

# Step 5：执行压测场景
k6 run scenarios/01_smoke.js
```

---

## 场景说明与成功标准

| 场景 | 文件 | VU | 时长 | p95 RT | 错误率 | 关注点 |
|------|------|-----|------|--------|--------|--------|
| 冒烟 | 01_smoke.js | 2 | 1min | — | 0% | 脚本无报错，接口正常返回 |
| 基准 | 02_load.js | 0→50→0 | 10min | < 500ms | < 1% | 50 VU 下的稳定基线 |
| 压力 | 03_stress.js | 0→200 | 渐增 | — | < 5% | 记录崩溃点 VU 数 |
| 尖峰 | 04_spike_rush.js | 200 | 短突发 | < 200ms | < 1% | 排队人数 ≤ maxQueueSize（5） |
| 浸泡 | 05_soak.js | 30 | 1h | < 500ms | < 1% | Heap 无单调递增，连接池无泄漏 |

冒烟测试通过后再跑后续场景。压力测试的目标是**找到崩溃点**，超过阈值属于预期内。

---

## 测试后清理 Redis

```bash
# 清理抢档期 ZSET（避免影响下次测试的原子性验证）
redis-cli --scan --pattern "schedule:rush:*" | xargs -r redis-cli DEL
```

数据库测试数据在下次 `run_tests.sh` 或手动清库时会一并清除，无需每次手动处理。
