# Nginx

## 一、DNS 解析

用户在浏览器输入 `https://your-domain.com`，第一步不是连接服务器，而是把域名翻译成 IP 地址。

### 解析过程（递归 + 迭代）

```
浏览器检查本地缓存（有缓存直接用）
    ↓ 没有
查操作系统 hosts 文件（/etc/hosts）
    ↓ 没有
查本地 DNS 解析器缓存（ISP 分配的 DNS 服务器）
    ↓ 没有
本地 DNS 解析器 → 根域名服务器（Root Server）
    ↓ 返回：管 .com 的顶级域名服务器地址
本地 DNS 解析器 → 顶级域名服务器（TLD Server，管 .com）
    ↓ 返回：管 your-domain.com 的权威域名服务器地址
本地 DNS 解析器 → 权威域名服务器（你在域名商配置的 DNS）
    ↓ 返回：your-domain.com 对应的 IP 地址
本地 DNS 解析器缓存结果，返回给浏览器
```

**递归 vs 迭代**：浏览器对本地 DNS 解析器是递归查询（"帮我查到底"）；本地 DNS 解析器对根服务器、TLD 服务器是迭代查询（"你不知道就告诉我下一个该问谁"）。

### TTL（Time To Live）

DNS 记录都有 TTL，单位秒。缓存存活期内不再重新查询。改 DNS 指向后，需要等旧 TTL 过期才能全网生效，这就是"DNS 生效需要时间"的原因。

### 本项目部署时需要配置

在域名商控制台添加 **A 记录**：`your-domain.com → 服务器公网 IP`。之后所有对 `your-domain.com` 的请求都会解析到你的服务器，由 Nginx 接收。

---

## 二、TLS/HTTPS 握手

DNS 解析拿到 IP 后，浏览器建立 TCP 连接（三次握手，见 tomcat.md），然后进行 TLS 握手，在正式传输数据前协商好加密方案。

### 为什么需要 TLS

HTTP 是明文传输，中间人可以直接读取或篡改内容。TLS 在 TCP 之上加了一层加密，解决：
- **保密性**：内容加密，第三方无法读取
- **完整性**：内容被篡改时能被发现（MAC 校验）
- **身份验证**：证书证明你访问的确实是真实服务器

### TLS 1.3 握手过程（现代浏览器默认）

```
Client → Server : ClientHello
    （支持的 TLS 版本、加密套件列表、随机数 Client Random、密钥交换参数）

Server → Client : ServerHello + Certificate + Finished
    （选定的加密套件、随机数 Server Random、服务器证书、服务器生成的密钥交换参数）

浏览器验证证书：
    1. 证书是否由受信任的 CA 签发（浏览器内置根证书列表）
    2. 证书域名是否匹配（your-domain.com）
    3. 证书是否在有效期内
    4. 证书是否被吊销（CRL / OCSP）

双方用密钥交换参数各自计算出相同的 会话密钥（Session Key）
    （ECDHE 算法：双方各生成临时密钥对，交换公钥，各自用私钥+对方公钥算出相同的共享密钥）

Client → Server : Finished（用会话密钥加密，证明自己拿到了正确密钥）

握手完成，后续通信全部用对称加密（AES-GCM 等）
```

**TLS 1.3 比 1.2 快**：握手只需 1-RTT（甚至 0-RTT 复用），1.2 需要 2-RTT。

### 对称加密 vs 非对称加密

| | 对称加密 | 非对称加密 |
|---|---|---|
| 密钥 | 加解密用同一把密钥 | 公钥加密，私钥解密 |
| 速度 | 快 | 慢（数学运算复杂） |
| 问题 | 如何安全传递密钥？ | 速度慢，不适合大量数据 |
| TLS 中的角色 | 握手完成后加密实际数据 | 握手阶段协商会话密钥 |

TLS 用非对称加密解决"密钥怎么安全传递"的问题，用协商出的对称密钥加密实际通信数据，兼顾安全与性能。

### 本项目中的 TLS 配置

TLS 在 **Nginx 层卸载**（Nginx 处理加解密），后端微服务之间走内网明文 HTTP，无需各自处理 TLS：

```nginx
ssl_certificate     /etc/letsencrypt/live/your-domain.com/fullchain.pem;
ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;
```

证书由 **Let's Encrypt + certbot** 免费签发，90 天自动续期。

---

## 三、基本概念

### Nginx 是什么

高性能的 HTTP 服务器和反向代理服务器，擅长处理高并发静态文件请求（异步非阻塞模型，单进程可维持数万连接）。

### 正向代理 vs 反向代理

**正向代理**：代理的是客户端，服务器不知道真实客户端是谁。

```
客户端 → [正向代理] → 服务器
```

典型场景：VPN、翻墙工具。客户端主动配置代理地址。

**反向代理**：代理的是服务器，客户端不知道真实服务器是谁。

```
客户端 → [反向代理 Nginx] → 后端服务器
```

典型场景：负载均衡、隐藏内网服务、SSL 卸载。客户端只知道 Nginx 的地址。

### 负载均衡

多台后端服务器同时提供服务，Nginx 按策略分发请求，避免单点压力过大。

### 动静分离

静态文件（HTML/JS/CSS/图片）由 Nginx 直接返回，不经过后端服务；动态请求（API 调用）才转发给后端处理，充分利用 Nginx 处理静态文件的性能优势。

---

## 二、静态资源 vs 动态资源

### 判断标准

**"服务器有没有现场计算"**，有就是动态，没有就是静态。

| | 静态资源 | 动态资源 |
|---|---|---|
| 服务方 | Nginx 直接读磁盘返回 | 后端程序查数据库、执行逻辑后生成 |
| 每次响应 | 所有人拿到的字节完全一样 | 根据参数、用户身份、数据库状态实时生成 |
| 典型内容 | HTML、JS、CSS、图片、字体 | JSON API 响应 |

### Vue SPA 的静态与动态

Vue 项目 `npm run build` 产出的 `dist/` 全是静态文件：

```
dist/
├── index.html              ← Nginx 直接返回
└── assets/
    ├── index-abc123.js     ← 打包好的所有组件/路由/状态管理代码
    └── index-abc123.css
```

**这些文件一旦构建就不再变化，所有用户拿到的是同一份。**

Vue 的"动态性"（点击按钮、展示不同数据）发生在浏览器里，由 JS 运行时驱动。JS 代码里虽然写了"发请求"的逻辑，但代码本身是固定的，Nginx 只管把这包代码交付给浏览器，不关心里面写了什么。

浏览器拿到 JS 后，Vue 启动，向 `/api/**` 发请求，这些请求才是动态的，Nginx 把它们转发给 Spring Boot 处理。

```
用户访问 https://your-domain.com/

第一步：拿 App 代码（静态）
  GET /          → Nginx → dist/index.html（读磁盘，Spring Boot 不参与）
  GET /assets/xxx.js → Nginx → dist/assets/xxx.js

第二步：App 运行后发 API 请求（动态）
  GET /api/merchant/search?keyword=妆娘
    → Nginx proxy_pass → Gateway:80 → Spring Boot 查 ES → 返回 JSON
```

---

## 三、常用命令

```bash
nginx -v                  # 查看版本号
nginx                     # 启动
nginx -s stop             # 立即停止（不等请求处理完）
nginx -s quit             # 优雅停止（等当前请求处理完再退出）
nginx -s reload           # 重新加载配置文件（不停服）
nginx -t                  # 检测配置文件语法是否正确
```

---

## 四、配置文件结构

默认位置：`/etc/nginx/nginx.conf`（Linux）

```nginx
# ── 全局块 ──────────────────────────────────────────────────────────
# 影响 Nginx 整体运行，如进程数、日志路径
worker_processes  auto;          # 工作进程数，auto = CPU 核心数
error_log  /var/log/nginx/error.log warn;

# ── events 块 ────────────────────────────────────────────────────────
# 影响 Nginx 与用户的网络连接
events {
    worker_connections  1024;    # 每个工作进程最大并发连接数
}

# ── http 块 ──────────────────────────────────────────────────────────
# 包含 HTTP 全局配置和所有 server 块
http {
    include       mime.types;
    default_type  application/octet-stream;
    sendfile      on;            # 高效文件传输，静态资源必开

    # ── server 块（可以有多个，每个对应一个虚拟主机）──────────────────
    server {
        listen       80;
        server_name  your-domain.com;

        # ── location 块（路径匹配规则）────────────────────────────────
        location / {
            root   /var/www/dist;
            index  index.html;
        }
    }
}
```

### location 匹配规则优先级

| 写法 | 类型 | 优先级 |
|---|---|---|
| `location = /exact` | 精确匹配 | 最高 |
| `location ^~ /prefix` | 前缀匹配（优先于正则） | 次高 |
| `location ~ \.php$` | 正则匹配（区分大小写） | 中 |
| `location ~* \.jpg$` | 正则匹配（不区分大小写） | 中 |
| `location /prefix` | 普通前缀匹配 | 最低 |

---

## 五、反向代理配置

```nginx
server {
    listen 80;
    server_name your-domain.com;

    # 动态请求：转发给后端
    location /api/ {
        proxy_pass http://127.0.0.1:8080;      # 目标地址
        proxy_set_header Host $host;            # 透传原始 Host
        proxy_set_header X-Real-IP $remote_addr; # 透传客户端真实 IP
    }

    # 静态资源：直接返回文件
    location / {
        root /var/www/dist;
        index index.html;
        try_files $uri $uri/ /index.html;      # SPA 路由兜底，找不到文件就返回 index.html
    }
}
```

**`try_files` 为什么必须配：**
Vue Router 使用 history 模式时，`/merchant/123` 这类路径在服务器上没有对应文件，不配 `try_files` 会直接 404。配了之后 Nginx 找不到文件就返回 `index.html`，由 Vue Router 在浏览器端处理这个路径。

---

## 六、负载均衡配置

```nginx
http {
    # 定义后端服务器组
    upstream backend {
        server 192.168.1.10:8080 weight=2;   # 权重 2，分配请求比例更多
        server 192.168.1.11:8080 weight=1;
        server 192.168.1.12:8080 backup;     # 备用，主服务器全挂时才启用
    }

    server {
        location /api/ {
            proxy_pass http://backend;        # 指向 upstream 名称
        }
    }
}
```

### 分配策略

| 策略 | 配置方式 | 说明 |
|---|---|---|
| 轮询（默认） | 不配置 | 请求按时间顺序依次分配 |
| 权重 | `weight=N` | 权重越高分配比例越大，适合性能不均的服务器 |
| IP hash | `ip_hash;` | 同一 IP 固定访问同一服务器，适合需要 session 粘滞的场景 |
| 最少连接 | `least_conn;` | 优先分配给当前连接数最少的服务器 |

---

## 七、本项目（MHP）中 Nginx 的职责

生产环境部署结构：

```
用户请求
  → Nginx（443/80，SSL 在这里卸载）
      ├── location /          → 直接返回 dist/ 静态文件（不经过 Spring Boot）
      └── location /api/      → proxy_pass Gateway:80
                                  → 按路径分发到对应微服务
```

完整配置参考：

```nginx
server {
    listen 443 ssl;
    server_name your-domain.com;

    ssl_certificate     /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;

    # 前端静态文件
    location / {
        root /var/www/mhp/dist;
        index index.html;
        try_files $uri $uri/ /index.html;
    }

    # API 请求转发给 Gateway
    location /api/ {
        proxy_pass http://127.0.0.1:80;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # WebSocket（原生 STOMP，注意不要写成 /ws/，见"实际遇到的问题"一节）
    location /ws {
        proxy_pass http://127.0.0.1:80;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}

# HTTP 重定向到 HTTPS
server {
    listen 80;
    server_name your-domain.com;
    return 301 https://$host$request_uri;
}
```

### Nginx 与 Spring Cloud Gateway 的分工

| | Nginx | Spring Cloud Gateway |
|---|---|---|
| 角色 | 基础设施层反向代理 | 应用层智能路由 |
| 路由目标 | 写死 IP:Port | 服务名（`lb://mhp-account`），自动服务发现 |
| 新增实例 | 需改配置 + reload | Nacos 自动感知，无需改动 |
| 自定义逻辑 | Lua 脚本 | Java Filter（鉴权、限流、日志） |
| 擅长 | SSL 卸载、静态文件、高吞吐 | 服务路由、业务过滤器 |

Nginx 不替代 Gateway，两者各司其职：Nginx 解决"外网到服务器"的问题，Gateway 解决"服务器内部请求如何路由到各微服务"的问题。

---

## 八、实际遇到的问题

### WebSocket 握手返回 301，连接失败

**现象**

部署上线后，浏览器控制台报错：

```
WebSocket connection to 'ws://host/ws' failed: Error during WebSocket handshake: Unexpected response code: 301
```

**根因：Nginx 的尾部斜杠自动重定向**

Nginx 有一个内置行为：若请求路径是 `/foo`（无尾部斜杠），而配置中存在 `location /foo/`（有尾部斜杠），Nginx 会自动将 `/foo` 301 重定向到 `/foo/`。这个设计本是为了规范化目录访问 URL。

问题配置：

```nginx
location /ws/ {   # 有尾部斜杠
    proxy_pass ...;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    ...
}
```

WebSocket 客户端连接的端点是 `ws://host/ws`（无尾部斜杠）。请求到达 Nginx 时：

1. `/ws` 不匹配 `location /ws/`（前缀不符）
2. Nginx 检测到存在 `location /ws/`，触发自动重定向
3. 返回 `301 → /ws/`
4. WebSocket 握手是特殊的 HTTP Upgrade 请求，收到 301 后不会重新发起 Upgrade，直接报连接失败

**为什么同样写了 `/api/`，API 请求没有这个问题？**

API 请求的路径永远带有子路径，例如 `/api/user/login`、`/api/booking`，这些路径自带 `/api/` 前缀（含斜杠），天然匹配 `location /api/`，Nginx 从不会收到裸 `/api` 请求。

而 WebSocket 连接的是端点本身，路径就是 `/ws`，没有任何后缀，因此会触发重定向。

原来用 SockJS 时也没遇到此问题，原因是 SockJS 连接时实际请求的路径是 `/ws/info`、`/ws/websocket` 等，天然带了斜杠。换成原生 WebSocket 后请求路径变成裸 `/ws`，才暴露了这个问题。

**修复**

去掉 location 的尾部斜杠：

```nginx
location /ws {    # 无尾部斜杠，直接匹配 /ws 路径
    proxy_pass http://localhost:8083;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;
}
```

```bash
sudo nginx -s reload   # 热重载，不中断现有连接
```
