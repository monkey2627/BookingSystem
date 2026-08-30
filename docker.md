# Docker 从原理到实战

---

## 一、Docker 解决了什么问题

### 经典困境："在我机器上能跑"

一个 Java 服务需要：特定版本的 JDK、MySQL、Redis、特定的环境变量、特定的配置文件。
换一台机器部署，版本不对、路径不对、依赖缺失，各种报错。

**Docker 的解法：** 把应用 + 它所有的依赖打包成一个"集装箱"（容器），
集装箱在任何支持 Docker 的机器上运行，行为完全一致。

```
没有 Docker：
  代码 + 依赖 = 在 A 机器能跑，B 机器不一定

有 Docker：
  镜像（Image）= 代码 + 依赖 + 运行环境
  → 在任何有 Docker 的机器上，容器行为完全一致
```

---

## 二、核心概念（先建心智模型）

### 2.1 镜像（Image）—— 模板，只读

镜像是一个只读的文件系统快照，包含了运行某个程序所需的一切：
操作系统文件、运行时、应用代码、配置。

类比：**镜像是类（Class），容器是实例（Object）**。
一个镜像可以启动 N 个容器，互不影响。

```bash
# 查看本机已有的镜像
docker images

# 输出示例：
# REPOSITORY    TAG       IMAGE ID       SIZE
# mysql         8.0       a3b6608898d6   596MB
# redis         7         xxxxxx         117MB
```

### 2.2 容器（Container）—— 运行中的进程

容器是镜像的运行实例。启动一个容器，本质上是启动了一个被隔离的进程。
容器有自己独立的文件系统、网络、进程空间，但共享宿主机的 Linux 内核。

```bash
# 查看运行中的容器
docker ps

# 查看所有容器（含已停止的）
docker ps -a

# 输出示例：
# CONTAINER ID  IMAGE     COMMAND   STATUS    PORTS             NAMES
# a1b2c3d4e5f6  mysql:8.0  ...      Up 2h     0.0.0.0:3306->... mhp-mysql
```

### 2.3 镜像的层（Layer）—— 为什么镜像下载这么快

镜像不是一个大文件，而是由多个只读层叠加而成。

```
mysql:8.0 镜像的层结构：
  Layer 4：MySQL 配置文件         (几 KB)
  Layer 3：MySQL 二进制文件       (200MB)
  Layer 2：Linux 系统库           (100MB)
  Layer 1：基础 OS (Debian slim)  (80MB)
```

**关键优势：层可以复用。**
如果你已经下载了 mysql:8.0，再下载 redis:7（同样基于 Debian）时，
共同的 OS 层不需要重新下载。

### 2.4 卷（Volume）—— 数据持久化

容器删除后，容器内的数据也会消失。Volume 是宿主机上的目录，
挂载到容器内部，数据存在宿主机上，容器重启/删除数据不丢失。

```
宿主机                    容器
/var/lib/docker/volumes/mysql-data  ←→  /var/lib/mysql（MySQL 数据目录）
```

### 2.5 网络（Network）—— 容器间如何通信

同一个 docker-compose 启动的容器，默认在同一个虚拟网络里，
可以用**容器名**直接互相访问（Docker 内置 DNS）。

```
# docker-compose.yml 里：
canal-server:
  environment:
    canal.instance.master.address: mysql:3306  ← 用容器名 "mysql"，不是 127.0.0.1

# Canal 容器内部，"mysql" 会被解析为 mhp-mysql 容器的 IP
```

### 2.6 Dockerfile —— 如何制作镜像

Dockerfile 是制作镜像的"食谱"，每一行指令生成一个层。

```dockerfile
# 本项目的 mhp-ai/Dockerfile
FROM python:3.11-slim        # 基础镜像（从这层开始）
WORKDIR /app                 # 设置工作目录
COPY requirements.txt .      # 把文件复制进镜像
RUN pip install -r requirements.txt   # 执行命令（生成新的层）
COPY . .                     # 复制所有代码
EXPOSE 8084                  # 声明容器监听的端口（仅文档作用）
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8084"]  # 容器启动命令
```

---

## 三、原理：Docker 怎么实现隔离的

Docker 不是虚拟机，它直接复用宿主机的 Linux 内核，
用内核的两个特性实现隔离：

### 3.1 Namespace —— 看不见彼此

Namespace 让每个容器以为自己独占了整个系统：

| Namespace | 隔离内容 | 效果 |
|-----------|----------|------|
| PID | 进程 ID | 容器内 PID=1，看不到宿主机其他进程 |
| NET | 网络 | 容器有自己的 IP、网卡、端口空间 |
| MNT | 文件系统 | 容器有自己的根目录 `/`，看不到宿主机文件 |
| UTS | 主机名 | 容器有自己的 hostname |

```bash
# 验证：进入容器看到的进程只有自己的
docker exec -it mhp-mysql ps aux
# 只看到 MySQL 进程，看不到宿主机的 Java 进程
```

### 3.2 Cgroups —— 限制资源

Cgroups（Control Groups）限制容器能使用多少 CPU、内存、磁盘 IO。

```bash
# 限制容器最多用 512MB 内存（ES 容器的配置）
# docker-compose.yml 中：
environment:
  - "ES_JAVA_OPTS=-Xms512m -Xmx512m"   # 限制 ES JVM 堆内存

# 也可以在 docker run 时指定：
docker run -m 512m mysql:8.0
```

### 3.3 对比虚拟机

```
虚拟机：
  宿主机硬件
    └── Hypervisor（VMware/VirtualBox）
        ├── 虚拟机A（完整 OS + 内核，1~2GB）
        └── 虚拟机B（完整 OS + 内核，1~2GB）

Docker 容器：
  宿主机硬件
    └── 宿主机 Linux 内核（共享）
        ├── 容器A（只有应用 + 依赖，几十 MB）
        └── 容器B（只有应用 + 依赖，几十 MB）
```

**结论：容器比虚拟机轻 10~100 倍，启动速度从分钟级到秒级。**

---

## 四、常用命令详解

### 4.1 镜像操作

```bash
# 从 Docker Hub 拉取镜像
docker pull mysql:8.0
docker pull redis:7
docker pull nginx:latest   # latest 是默认 tag

# 查看本机镜像列表
docker images

# 删除镜像（必须先停止所有使用该镜像的容器）
docker rmi mysql:8.0

# 构建镜像（在 Dockerfile 所在目录执行）
# -t 指定镜像名和 tag，. 表示 Dockerfile 在当前目录
docker build -t mhp-ai:1.0 .

# 本项目 ES 镜像构建（Dockerfile 在上级目录）
docker build -t mhp-es -f Dockerfile.es .
```

### 4.2 容器生命周期

```bash
# 启动容器（最基础）
docker run mysql:8.0

# 常用选项组合
docker run \
  -d \                              # detach：后台运行（不阻塞终端）
  --name mhp-mysql \                # 给容器起名，方便后续引用
  -p 3306:3306 \                    # 端口映射：宿主机3306 → 容器3306
  -e MYSQL_ROOT_PASSWORD=123456 \   # 传入环境变量
  -v mysql-data:/var/lib/mysql \    # 挂载 Volume
  mysql:8.0

# 停止容器（发送 SIGTERM，优雅停止）
docker stop mhp-mysql

# 强制停止（发送 SIGKILL，立即杀死）
docker kill mhp-mysql

# 启动已停止的容器
docker start mhp-mysql

# 重启容器
docker restart mhp-mysql

# 删除容器（必须先 stop）
docker rm mhp-mysql

# 停止并删除（-f 强制）
docker rm -f mhp-mysql

# 删除所有已停止的容器
docker container prune
```

### 4.3 进入容器内部

`exec` 是 execute（执行）的缩写，意思是**在一个已经运行的容器里执行一条命令**。
关键是"已经运行"—— 容器必须是启动状态，`exec` 不会创建新容器。

对比容易混淆的三个命令：

| 命令 | 做什么 |
|------|--------|
| `docker run mysql:8.0` | 用镜像**创建并启动**一个新容器 |
| `docker start mhp-mysql` | **启动**一个已停止的容器 |
| `docker exec mhp-mysql xxx` | 在**已运行**的容器里执行命令 |

```bash
# 进入容器，打开交互式 shell
docker exec -it mhp-mysql bash
docker exec -it mhp-redis sh     # 精简镜像没有 bash，用 sh

# -i：保持 stdin 开放（交互）
# -t：分配伪终端（让输出格式正常）
# 两者合写 -it，缺一个你输入的内容就看不到或无法输入

# 完整拆解：docker exec -it mhp-mysql mysql -uroot -p222333dyh
# docker exec          → 在容器里执行命令
# -it                  → 交互式终端
# mhp-mysql            → 目标容器名
# mysql -uroot -p222333dyh → 在容器里运行的程序（MySQL 客户端）+ 参数

# 不加 -it：直接执行命令拿结果，适合非交互式操作
docker exec mhp-mysql mysqldump -uroot -p222333dyh mhp > backup.sql
docker exec mhp-redis redis-cli ping        # 返回 PONG
docker exec mhp-redis redis-cli keys "*"   # 查看所有 Redis key
```

为什么要用 `exec` 而不是直接输命令：MySQL 装在容器里，宿主机上没有 `mysql` 这个程序，
`exec` 相当于借用容器里的程序来执行。

### 4.4 查看日志

```bash
# 查看容器日志
docker logs mhp-mysql

# 实时追踪（类似 tail -f）
docker logs -f mhp-mysql

# 只看最后 50 行
docker logs --tail 50 mhp-mysql

# 带时间戳
docker logs -t mhp-mysql

# 组合使用
docker logs -f --tail 100 mhp-elasticsearch
```

### 4.5 查看容器状态

```bash
# 查看运行中的容器
docker ps

# 查看所有容器（含已停止）
docker ps -a

# 查看容器资源使用（CPU、内存、网络）
docker stats

# 查看单个容器的详细信息（IP、挂载、环境变量等）
docker inspect mhp-mysql

# 只看容器 IP
docker inspect mhp-mysql | grep IPAddress

# 查看容器内运行的进程
docker top mhp-mysql
```

### 4.6 端口映射详解

```bash
# 格式：-p 宿主机端口:容器端口
-p 3306:3306    # 宿主机 3306 → 容器 3306（MySQL）
-p 8088:8080    # 宿主机 8088 → 容器 8080（XXL-Job Admin）
-p 15672:15672  # 宿主机 15672 → 容器 15672（RabbitMQ 管理台）

# 本项目的端口映射一览（来自 docker-compose.yml）：
# mysql:      3306:3306
# redis:      6379:6379
# rabbitmq:   5672:5672, 15672:15672
# nacos:      8848:8848, 9848:9848
# xxl-job:    8088:8080   ← 注意：宿主机 8088 对应容器内 8080
# es:         9200:9200
# kibana:     5601:5601
# canal:      11111:11111
# mhp-ai:     8084:8084
```

**访问规则：**
- 宿主机上的 Java 服务（application.yaml）用**宿主机端口**（如 8088）
- 同一 docker-compose 里的容器互访用**容器名 + 容器端口**（如 `mysql:3306`）

### 4.7 Volume（数据卷）

```bash
# 查看所有 Volume
docker volume ls

# 查看 Volume 详情（在宿主机哪个路径）
docker volume inspect mysql-data
# 输出 "Mountpoint": "/var/lib/docker/volumes/mysql-data/_data"

# 删除 Volume（会丢数据！）
docker volume rm mysql-data

# 删除所有未使用的 Volume
docker volume prune
```

### 4.8 网络

```bash
# 查看 Docker 网络列表
docker network ls

# 查看某个网络的详情（哪些容器在里面，各自的 IP）
docker network inspect mhp_default

# docker-compose 会自动创建一个名为 "项目名_default" 的网络
# 同一 compose 的容器自动加入，可以用容器名互相访问
```

---

## 五、Docker Compose 详解

### 5.1 为什么需要 Compose

手动一条条 `docker run` 管理 9 个容器太麻烦，还要记住每个容器的参数、依赖顺序。
Compose 用一个 YAML 文件定义所有容器，一条命令全部搞定。

### 5.2 docker-compose.yml 结构解析

```yaml
version: '3.8'          # Compose 文件格式版本

services:               # 定义所有容器
  mysql:                # 服务名（同时也是容器间 DNS 名）
    image: mysql:8.0    # 使用哪个镜像
    container_name: mhp-mysql  # 容器的实际名字（docker ps 里显示的）
    environment:        # 环境变量（等价于 -e）
      MYSQL_ROOT_PASSWORD: 222333dyh
    ports:              # 端口映射（等价于 -p）
      - "3306:3306"
    volumes:            # 挂载（等价于 -v）
      - mysql-data:/var/lib/mysql
    healthcheck:        # 健康检查：Docker 定期执行这个命令
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s     # 每 10 秒检查一次
      retries: 5        # 失败 5 次才标记为 unhealthy

  canal-server:
    depends_on:         # 依赖关系：mysql 健康后才启动 canal
      mysql:
        condition: service_healthy

volumes:                # 声明具名 Volume
  mysql-data:           # Docker 管理的持久化存储
```

### 5.3 Compose 核心命令

#### build 和 up 的分工

| 命令 | 职责 | 类比 |
|------|------|------|
| `docker compose build` | 把 Dockerfile 烤成镜像（备原材料） | 备菜 |
| `docker compose up -d` | 用现有镜像把容器跑起来 | 开饭 |

**`up` 不会重新 build 的原因：**
`up` 判断"镜像存不存在"只看本地缓存有没有，不管 Dockerfile 改没改。
构建镜像是重操作（要下载插件、执行命令），`up` 的职责只是"把服务跑起来"，
不应该每次都触发耗时构建。改了 Dockerfile 想生效，必须显式触发：

```bash
# 方式1：先 build，再 up
docker compose build elasticsearch
docker compose up -d

# 方式2：up 时加 --build 强制重新构建
docker compose up -d --build elasticsearch
```

**`up -d` 的完整执行流程：**

```
docker compose up -d
        │
        ▼
1. 读取 docker-compose.yml，解析所有服务定义
        │
        ▼
2. 对每个服务，检查镜像是否存在于本地：
   ├── 写了 image: mysql:8.0
   │     └── 本地有？→ 直接用 / 本地没有？→ 去 Docker Hub 拉取
   └── 写了 build: ...
         └── 本地有构建好的镜像？→ 直接用（不重新 build）
             本地没有？→ 自动 build
        │
        ▼
3. 对每个服务，检查容器是否已存在：
   ├── 容器不存在      → 创建并启动
   ├── 容器存在且运行中 → 什么都不做
   └── 容器存在但已停止 → 重新启动
        │
        ▼
4. -d（detach）：后台运行，不阻塞终端
   不加 -d 则日志打在当前终端，Ctrl+C 就全停了
```

#### 常用命令

```bash
# 以下命令都在 docker-compose.yml 所在目录执行

# 启动所有服务（后台）
docker compose up -d

# 只启动某个服务
docker compose up -d mysql redis

# 查看所有服务状态
docker compose ps

# 查看某个服务的日志
docker compose logs -f mysql
docker compose logs -f elasticsearch

# 停止所有服务（保留容器和数据）
docker compose stop

# 停止并删除容器（保留 Volume 数据）
docker compose down

# 停止、删除容器、同时删除 Volume（数据全清，慎用）
docker compose down -v

# 重启某个服务
docker compose restart mysql

# 重新构建镜像（Dockerfile 改了之后用）
docker compose build elasticsearch
docker compose build --no-cache elasticsearch   # 强制不用缓存

# 查看服务的实时资源占用
docker compose top
```

### 5.4 .env 文件 —— 敏感信息不写死在 compose 里

```bash
# .env 文件（与 docker-compose.yml 同目录）
DEEPSEEK_API_KEY=sk-xxxxxxxx
MYSQL_PASSWORD=222333dyh

# docker-compose.yml 中引用
mhp-ai:
  environment:
    - DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}   # 从 .env 读取
```

**为什么 Compose 知道要去 `.env` 读？**

不需要任何配置，这是 Docker Compose 的**内置约定**，启动时自动执行三步：

```
1. 读取当前目录下的 .env，把 KEY=VALUE 加载进内存
2. 扫描 docker-compose.yml，把所有 ${KEY} 替换成实际值
3. 用替换后的内容启动容器
```

用这个命令可以看到变量替换后的最终结果（不会真正启动）：

```bash
docker compose config
# ${DEEPSEEK_API_KEY} 会被替换成 .env 里的实际值
```

文件名**必须**是 `.env`，这是硬性规定。如果要用其他文件名，需要显式指定：

```bash
docker compose --env-file ./config/prod.env up -d
```

这个设计叫**约定优于配置**（Convention over Configuration）：框架规定好"去哪里找配置"，遵守约定就自动生效，不需要额外告知。Spring Boot 启动时自动找 `application.yaml` 是同一个道理。

### 5.5 extra_hosts —— 容器访问宿主机

```yaml
# 问题：容器内如何访问宿主机上运行的 Java 服务？
# 解法：extra_hosts 把 host.docker.internal 解析为宿主机 IP

mhp-ai:
  extra_hosts:
    - "host.docker.internal:host-gateway"
  environment:
    - GATEWAY_URL=http://host.docker.internal:80   # 容器内访问宿主机 80 端口

# host-gateway 是一个特殊值，Docker 自动替换为宿主机的 IP
# Windows/Mac 的 Docker Desktop 默认支持 host.docker.internal
# Linux 必须显式加 extra_hosts 才生效（本项目已修复）
```

---

## 六、本项目 Docker 架构详解

```
宿主机（Linux 服务器）
│
├── Java 进程（直接运行在宿主机，不在容器里）
│   ├── mhp-gateway  :8080
│   ├── mhp-account  :8081
│   ├── mhp-booking  :8082
│   └── mhp-social   :8083
│
└── Docker 管理的容器（通过端口映射暴露给宿主机）
    ├── mhp-mysql      :3306  ← Java 连 localhost:3306
    ├── mhp-redis      :6379  ← Java 连 localhost:6379
    ├── mhp-rabbitmq   :5672  ← Java 连 localhost:5672
    ├── mhp-nacos      :8848  ← Java 连 localhost:8848
    ├── mhp-xxl-job    :8088  ← Java 连 localhost:8088（容器内是 8080）
    ├── mhp-elasticsearch :9200 ← Java 连 localhost:9200
    ├── mhp-kibana     :5601
    ├── mhp-canal      :11111 ← Java 连 localhost:11111
    └── mhp-ai         :8084  ← 自身就是容器，通过 host.docker.internal 访问 Java 服务
```

**为什么 Java 服务不放进容器？**
开发/调试方便：改代码后只需重启一个 JAR，不用重新 build 镜像。
中间件（MySQL、Redis 等）状态稳定，适合容器化。
应用服务改动频繁，容器化带来的收益不如麻烦多（此阶段）。

---

## 七、实战场景速查

### 场景 1：查看 MySQL 里有哪些表

```bash
docker exec -it mhp-mysql mysql -uroot -p222333dyh
# 进入 MySQL 后：
USE mhp;
SHOW TABLES;
DESCRIBE booking;   # 查看 booking 表结构
```

### 场景 2：Redis 里有什么数据

```bash
docker exec -it mhp-redis redis-cli

# 常用命令：
KEYS *                    # 列出所有 key（生产慎用）
KEYS merchant:*           # 模糊搜索
GET merchant:1            # 查看某个 key 的值
TTL merchant:1            # 查看剩余过期时间（秒）
DEL merchant:1            # 删除某个 key（测试时用）
FLUSHDB                   # 清空当前库（慎用）
```

### 场景 3：查看 RabbitMQ 队列积压

```bash
# 方式1：管理台（更直观）
# 浏览器访问 http://<服务器IP>:15672  admin/123456

# 方式2：命令行
docker exec mhp-rabbitmq rabbitmqctl list_queues name messages
```

### 场景 4：ES 查询 merchant 索引

```bash
# 查看 merchant 索引的所有文档数量
curl http://localhost:9200/merchant/_count

# 查看前 3 个文档
curl "http://localhost:9200/merchant/_search?size=3" | python3 -m json.tool

# 搜索昵称包含"妆娘"的商家
curl -X GET "http://localhost:9200/merchant/_search" \
  -H "Content-Type: application/json" \
  -d '{"query": {"match": {"nickname": "妆娘"}}}'

# 删除并重建索引（数据清空后重新 init）
curl -X DELETE http://localhost:9200/merchant
curl -X POST http://localhost:8081/internal/merchant/es/init
```

### 场景 5：容器磁盘占用太大

```bash
# 查看 Docker 整体占用
docker system df

# 输出示例：
# TYPE            SIZE      RECLAIMABLE
# Images          3.2GB     1.1GB
# Containers      45MB      0B
# Volumes         2.1GB     0B
# Build Cache     512MB     512MB

# 清理构建缓存（不影响运行）
docker builder prune

# 清理所有停止的容器 + 未使用的镜像 + 网络（谨慎）
docker system prune
```

### 场景 6：容器启动失败，看原因

```bash
# 查看容器日志（最有用）
docker logs mhp-canal

# 查看容器退出状态
docker inspect mhp-canal | grep -A5 "State"

# 查看 docker-compose 整体状态
docker compose ps
# Status 列：Up = 正常，Exited(1) = 异常退出，Restarting = 反复重启
```

### 场景 7：数据备份

```bash
# 备份 MySQL 数据库
docker exec mhp-mysql mysqldump -uroot -p222333dyh mhp > /root/backup_mhp_$(date +%Y%m%d).sql

# 恢复
docker exec -i mhp-mysql mysql -uroot -p222333dyh mhp < /root/backup_mhp_20260829.sql
```

---

## 八、一张图理解 Docker 核心流程

```
Dockerfile
    │ docker build
    ▼
镜像（Image）── docker push ──▶ Docker Hub / 私有仓库
    │
    │ docker run / docker compose up
    ▼
容器（Container）
    │
    ├── 进程在 Namespace 隔离的空间里运行
    ├── Cgroups 限制 CPU/内存使用
    ├── 容器内文件系统（读写层叠加在只读镜像层之上）
    └── Volume 挂载 ──▶ 宿主机目录（数据持久化）
          │
          ├── 容器删了，Volume 数据还在
          └── docker compose down -v 才会删 Volume
```

---

## 九、常见误区

| 误区 | 正确理解 |
|------|----------|
| "容器 = 虚拟机" | 容器是隔离的进程，共享宿主机内核；虚拟机有独立内核 |
| "容器 IP 就是 127.0.0.1" | 容器有自己的 IP（172.17.x.x），宿主机通过端口映射访问 |
| "容器停了数据就没了" | 只有写在容器层的数据会丢；挂了 Volume 的数据不丢 |
| "`docker compose down` 会删数据" | `down` 只删容器，加 `-v` 才删 Volume |
| "改了 Dockerfile 自动生效" | 需要 `docker compose build` 重新构建镜像 |
| "容器名就是域名，全局可用" | 只在同一个 Docker 网络内，容器名才能当域名用 |
