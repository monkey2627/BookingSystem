# Tomcat 系统笔记

---

## 一、基础概念

**软件架构**：C/S（客户端/服务器）、B/S（浏览器/服务器）

**资源分类**：
- **静态资源**：所有用户访问结果相同，浏览器直接解析（html、css、js、jpg）
- **动态资源**：同一资源不同用户访问结果不同（需要服务器处理逻辑）

**网络通信三要素**：协议、IP（主机）、端口

**Web 服务器**：可以部署 Web 项目、接收浏览器请求的软件；Tomcat 是开源免费的 Web 服务器，实现了 Servlet 规范。

### Tomcat 文件结构

| 目录 | 作用 |
|------|------|
| `bin/` | 可执行脚本（startup.sh / shutdown.sh） |
| `conf/` | 配置文件（server.xml 核心配置、web.xml 默认 servlet 映射） |
| `lib/` | Tomcat 自身依赖 jar |
| `webapps/` | 默认 Web 项目部署目录，将 WAR 包放入此处即可部署 |
| `logs/` | 日志 |
| `work/` | JSP 编译后的 class 文件（Jasper 输出目录） |

---

## 二、HTTP 工作原理与 TCP 三次握手

HTTP 是应用层协议，建立在 TCP 之上。每次 HTTP 通信之前，TCP 必须先完成三次握手建立连接。

### TCP 三次握手

> **目标**：建立双向可靠连接，确认双方收发能力正常，协商初始序列号 ISN。

**关键标记位**：
- `SYN`：同步，初始化序列号；**SYN 报文消耗一个序列号**
- `ACK`：确认，ack 号表示期望收到的下一个字节的序号
- `ISN`：Initial Sequence Number，双方各自随机生成

#### 三次握手过程（C=客户端，S=服务端）

| 次序 | 方向 | 报文内容 | 含义 |
|------|------|----------|------|
| 第一次 | C → S | SYN=1, seq=ISN(c) | 我要建连接，我的初始序号是 ISN(c) |
| 第二次 | S → C | SYN=1, ACK=1, seq=ISN(s), ack=ISN(c)+1 | 收到，我也要建连接，我的初始序号是 ISN(s) |
| 第三次 | C → S | ACK=1, seq=ISN(c)+1, ack=ISN(s)+1 | 确认收到，双向连接正式建立 |

**状态流转**：
- 客户端：`CLOSED` → `SYN_SENT` → `ESTABLISHED`
- 服务端：`LISTEN` → `SYN_RCVD` → `ESTABLISHED`

#### 为什么是 3 次而不是 2 次？（面试高频）

核心：**防止历史失效的旧 SYN 报文让服务端建立无效连接浪费资源。**

场景：一个早期的旧 SYN 因网络延迟迟到到达服务端。
- **2 次握手**：服务端回 SYN+ACK 就直接建立连接，但客户端已不需要，不会回复，服务端一直等待浪费资源。
- **3 次握手**：服务端必须等第三次 ACK，客户端不会回，服务端超时释放，不建立无效连接。

另一角度：
- 1 次：只有客户端知道自己能发
- 2 次：客户端确认双向通，但服务端不知道自己发的包客户端能否收到
- 3 次：双方都确认「我发的对方收得到，对方发的我收得到」

#### 易错考点

1. SYN 消耗一个序号，所以 ack = ISN + 1
2. 第二次握手同时完成两件事：同步自己 ISN + 确认对方
3. **第三次 ACK 可以携带业务数据；前两次 SYN 不能携带数据**

### TCP 四次挥手

> **目标**：全双工连接，双方各自独立关闭自己的发送方向，所以需要 4 次。

| 次序 | 方向 | 报文内容 | 含义 |
|------|------|----------|------|
| 第一次 | C → S | FIN=1, seq=u | 我没有数据要发了，请求关闭 |
| 第二次 | S → C | ACK=1, ack=u+1 | 收到，但我可能还有数据要发 |
| 第三次 | S → C | FIN=1, seq=w | 我也没有数据要发了，请求关闭 |
| 第四次 | C → S | ACK=1, ack=w+1 | 收到，连接关闭 |

**状态流转**：
- 客户端：`ESTABLISHED` → `FIN_WAIT_1` → `FIN_WAIT_2` → `TIME_WAIT` → `CLOSED`
- 服务端：`ESTABLISHED` → `CLOSE_WAIT` → `LAST_ACK` → `CLOSED`

#### 为什么是 4 次而不是 3 次？

三次握手时，服务端可以把 SYN 和 ACK 合并成一次发送（同时完成"确认对方"和"同步自己"）。

四次挥手时，服务端收到客户端的 FIN 后，**只能先回 ACK**（表示"我知道你要关了"），但此时服务端可能还有数据没发完，必须等数据发完才能发 FIN，所以 ACK 和 FIN 无法合并，必须分两次。

#### TIME_WAIT 为什么要等 2MSL？（面试高频）

MSL（Maximum Segment Lifetime）= 报文在网络中的最大存活时间，一般 1 分钟。

客户端发出第四次 ACK 后等待 2MSL 再关闭，原因：
1. **防止最后一个 ACK 丢失**：若 ACK 丢失，服务端会重发 FIN，客户端需要还在等待才能重发 ACK。2MSL = ACK 传输时间 + FIN 重传时间，足够容纳一次重传。
2. **确保旧连接的报文全部消亡**：防止本次连接的延迟报文被新连接误收。

---

## 三、Tomcat 整体架构

Tomcat 的顶层架构 = **连接器（Connector）+ 容器（Container）**，两者合称一个 **Service**，多个 Service 组成 **Server**，由 **Catalina** 统一管理。

```
Server
└── Service（可多个）
    ├── Connector（可多个）：对外，处理网络通信
    └── Container（Engine）：对内，处理业务逻辑
            └── Host（虚拟主机）
                    └── Context（Web 应用）
                            └── Wrapper（Servlet）
```

### 3.1 连接器（Coyote）

**职责**：封装底层网络通信（Socket），将收到的请求封装为 `ServletRequest` 交给容器。

**支持的 IO 模型（传输层）**：NIO（默认）、NIO2（异步IO）、APR

**支持的应用层协议**：HTTP/1.1、AJP、HTTP/2

#### 连接器内部组件

```
Endpoint（网络端点）
  ├── Acceptor：监听 Socket 连接请求
  └── SocketProcessor：处理接收到的 Socket（调用线程池）
        ↓
Processor（协议解析）
  解析 HTTP 报文 → Coyote Request / Response 对象
        ↓
CoyoteAdapter（适配器）
  Coyote Request → HttpServletRequest（Servlet 规范对象）
        ↓
Container（容器）
```

> Endpoint + Processor 合称 **ProtocolHandler**（协议处理器）。

**为什么要有 Adapter？不能让 Processor 直接封装成 ServletRequest？**

这是**单一职责原则（SRP）**的体现：
- `Processor` 只负责理解网络协议（HTTP 报文解析），它产出的是 Coyote 自己内部的 `Request` 对象，与 Servlet 规范无关。
- `CoyoteAdapter` 负责在 Coyote 世界和 Servlet 世界之间做桥接，将 Coyote Request → `HttpServletRequest`。
- 好处：同一套 Container 可以对接不同的 Connector（HTTP/1.1、HTTP/2、AJP），只需要对应的 Adapter 做转换；Processor 不用知道 Servlet 规范。这是**适配器模式**。

#### 线程池

Tomcat 的线程池扩展自 `ThreadPoolExecutor`，关键区别：
- 标准 JDK 线程池：核心线程满 → 放队列 → 队列满 → 扩容 → 达到最大线程数 → 拒绝
- Tomcat 线程池：核心线程满 → **先扩容到最大线程数** → 再放队列 → 队列也满 → 拒绝

> Tomcat 优先用线程处理而不是先排队，更适合 Web 场景（响应及时优先于吞吐量）。

### 3.2 容器（Catalina）

容器的四层嵌套结构：

| 层级 | 名称 | 职责 |
|------|------|------|
| Engine | 引擎 | 处理所有请求，一个 Service 对应一个 Engine |
| Host | 虚拟主机 | 代表一个域名/IP，一个 Engine 可有多个 Host |
| Context | Web 应用 | 代表一个 Web 应用（WAR 包），一个 Host 可有多个 Context |
| Wrapper | Servlet 封装 | 代表一个具体的 Servlet，是最小处理单元 |

**Catalina**：负责管理整个 Server 的生命周期，解析 server.xml，初始化所有组件。

---

## 四、Tomcat 请求处理流程（Pipeline-Valve 责任链）

### 4.1 什么是责任链？

**责任链模式（Chain of Responsibility）**：把请求的处理者排成一条链，请求沿链依次传递，每个处理者可以处理或选择继续向下传递。

Tomcat 用 **Pipeline（管道）+ Valve（阀门）** 实现责任链：
- 每个容器层级（Engine / Host / Context / Wrapper）都有一条 **Pipeline**
- Pipeline 里包含多个 **Valve**（可通过配置添加自定义 Valve，如日志、鉴权）
- 每条 Pipeline 的最后一个 Valve 是 **BasicValve**（内置），负责调用下一层容器的 Pipeline

```
Connector 发来 Request
    ↓
Engine Pipeline → [自定义Valve...] → StandardEngineValve（BasicValve）
                                               ↓
                               Host Pipeline → [自定义Valve...] → StandardHostValve
                                                                          ↓
                                               Context Pipeline → [...] → StandardContextValve
                                                                                    ↓
                                                           Wrapper Pipeline → [...] → StandardWrapperValve
                                                                                              ↓
                                                                                    Servlet.service()
```

**现实意义**：可以在任意层级插入自定义 Valve 实现横切关注点（日志、限流、访问控制），而不侵入业务代码。这和 Spring MVC 的拦截器（Interceptor）、AOP 思想类似。

### 4.2 如何通过配置找到应该转发给谁？

**在第几步找？** 整个映射查找在进入容器后、到达 Servlet 之前完成，由 Tomcat 的 **Mapper 组件**负责。

**Mapper 组件**在 Tomcat 启动时就构建好了一张"URL → 容器"的高效映射表，请求来时直接查表，不用遍历。

**查找步骤**：

1. **Engine 层**：根据请求的 `Host` 请求头匹配虚拟主机（Host）
2. **Host 层**：根据 URL 前缀（Context Path）匹配 Web 应用（Context）
3. **Context 层**：根据 Servlet 映射规则匹配具体 Servlet（Wrapper）
   - 精确匹配 `/api/user` > 路径匹配 `/api/*` > 扩展名匹配 `*.do` > 默认 Servlet `/`

**在哪配置？**

| 场景 | 配置位置 |
|------|---------|
| 传统 WAR 包 | `web.xml` 的 `<servlet-mapping>` 标签 |
| Servlet 3.0+ | `@WebServlet("/path")` 注解 |
| Spring Boot | `DispatcherServletAutoConfiguration` 自动注册 DispatcherServlet 到 `/`，之后由 Spring MVC 的 `HandlerMapping` 再做一次内部路由 |

---

## 五、Tomcat 启动流程

```
main() → Bootstrap.main()
    ↓ 反射创建 Catalina 对象（catalinaDeamon）
Catalina.load()
    ↓ 解析 server.xml，用 Digester 创建 Server/Service/Connector/Engine 等对象
Catalina.start()
    ↓ 调用 Server.start() → Service.start() → Connector.start() + Container.start()
所有组件实现 Lifecycle 接口
    ↓ 统一生命周期管理（init → start → stop → destroy）
监听端口，等待请求
```

**Lifecycle 接口**：Tomcat 中所有组件（Server、Service、Connector、Engine…）都实现了该接口，统一管理 `init → start → stop → destroy` 生命周期。

---

## 六、Jasper（JSP 引擎）

**Jasper 是 Tomcat 内置的 JSP 编译器**，将 `.jsp` 文件编译为 Java Servlet 类，再由 Servlet 生成 HTML 响应给客户端。

**工作流程**：
```
浏览器请求 xxx.jsp
    ↓
Jasper 检测 jsp 文件是否改变
    ↓ 是（或首次请求）
编译 → xxxServlet.java → xxxServlet.class
    ↓
执行 Servlet → 生成 HTML → 返回浏览器
```

**编译产物**放在 `work/` 目录，再次请求时直接用缓存的 class，不重新编译。

**现代项目（如本项目）基本不使用 JSP**：前后端分离后，后端只返回 JSON，前端由 Vue/React 渲染，Jasper 形同虚设。

---

## 七、DispatcherServlet 是 Tomcat 提供的吗？

**不是，DispatcherServlet 是 Spring MVC 提供的。**

### 7.0 Servlet 规范是二者之间的契约

三者关系：

```
Servlet 规范（javax.servlet / jakarta.servlet，Java 官方定义的接口标准）
        ↑ 实现                              ↑ 遵守
Tomcat（Servlet 容器）             Spring MVC（Web 框架）
负责"运行 Servlet"                  提供 DispatcherServlet
创建 HttpServletRequest/Response    DispatcherServlet 实现 Servlet 接口
```

- **Tomcat** 实现了 Servlet 规范，是"插座（容器）"——它只认识 `Servlet` 接口，不管里面是谁写的。
- **DispatcherServlet** 是 Spring 团队写的类（`spring-webmvc` 包），它的继承链遵守了 Servlet 规范：
  ```
  DispatcherServlet
    → FrameworkServlet
      → HttpServletBean
        → HttpServlet       ← Servlet 规范提供（Tomcat 认识这个）
          → GenericServlet
            → Servlet       ← 顶层接口
  ```
- Tomcat 完全不知道 DispatcherServlet 的存在，它只认识最顶层的 `Servlet` 接口。只要实现了这个接口，Tomcat 就能运行它。

**类比**：Servlet 规范是"插座标准"，Tomcat 是"插座（容器）"，DispatcherServlet 是"符合插座标准的电器（由 Spring 生产）"——插座不管电器是谁造的，接口对上就能用。

### 7.0.1 DispatcherServlet 就是 Tomcat 链路的终点

把完整的 Tomcat 处理链路和 DispatcherServlet 的位置对应起来：

```
Connector（Coyote）
    ↓ HttpServletRequest / HttpServletResponse
Engine Pipeline → Host Pipeline → Context Pipeline → Wrapper Pipeline
    ↓
Wrapper.service()
    ↓                         ← Tomcat 的工作到此结束，把请求"送到门口"
DispatcherServlet.service()   ← 链路终点，Tomcat 只管调用这一行
    ↓ 进入 Spring MVC 内部（Tomcat 完全不知道下面发生了什么）
HandlerMapping → Interceptor → Controller → GlobalExceptionHandler → JSON 返回
```

**Wrapper 是对 Servlet 的封装**，一个 Wrapper 对应一个 Servlet。Spring Boot 只注册了一个 DispatcherServlet（映射到 `/`），所以 Tomcat 的 Pipeline 链路终点始终是它。

DispatcherServlet 之后的 HandlerMapping、拦截器、Controller、GlobalExceptionHandler 这些全是 **Spring MVC 在 DispatcherServlet 内部的事**，Tomcat 完全不知道，它只负责把请求送到门口，剩下的是 Spring 的世界。

---

## 八、Spring Boot 内置 Tomcat + DispatcherServlet

### 8.1 传统独立 Tomcat 的工作方式

```
1. 手动启动 Tomcat 进程
2. 将 WAR 包放入 webapps/ 目录
3. Tomcat 解析 web.xml，找到 DispatcherServlet 的 <servlet-mapping>
4. 初始化 DispatcherServlet → 创建 Spring ApplicationContext（WebApplicationContext）
5. 接收请求 → Tomcat 处理 → 路由到 DispatcherServlet
```

**生命周期归属**：Tomcat 拥有主控权，Spring ApplicationContext 是 Tomcat 的"租客"。

### 8.2 Spring Boot 内置 Tomcat 的工作方式

```
1. 执行 main() → SpringApplication.run()
2. Spring 创建 ApplicationContext
3. ApplicationContext 启动过程中，发现 spring-boot-starter-web 依赖
4. 触发 TomcatServletWebServerFactory → 以编程方式创建内嵌 Tomcat 实例
5. 自动注册 DispatcherServlet（由 DispatcherServletAutoConfiguration 完成）
6. 启动内嵌 Tomcat，监听端口（默认 8080）
```

**生命周期归属**：Spring ApplicationContext 拥有主控权，Tomcat 是 Spring 的"插件"。

### 8.3 DispatcherServlet 在内置 Tomcat 中的角色

Spring Boot 通过 `DispatcherServletAutoConfiguration` 自动完成以下操作（无需 web.xml）：

```java
// Spring Boot 内部等价于做了这些（简化伪代码）
ServletRegistrationBean<DispatcherServlet> registration = 
    new ServletRegistrationBean<>(new DispatcherServlet(), "/");
// "/" 表示拦截所有请求，相当于 web.xml 里的 <url-pattern>/</url-pattern>
```

**DispatcherServlet 内部处理流程**（Spring MVC 核心）：

```
Tomcat 调用 DispatcherServlet.service()
    ↓
doDispatch()
    ↓
HandlerMapping.getHandler(request)
    ↓ 找到对应的 Controller 方法（@RequestMapping 路由）
HandlerAdapter.handle()
    ↓ 执行 Controller 方法，得到 ModelAndView 或直接写入 Response
（如果有 @ResponseBody / REST）
    ↓
HttpMessageConverter 序列化返回值为 JSON / 其他格式
    ↓
写入 HttpServletResponse → Tomcat 发回客户端
```

**拦截器的位置**：在 HandlerMapping 找到 handler 之后、HandlerAdapter 执行之前，`HandlerInterceptor.preHandle()` 被调用。这是 Spring MVC 层面的拦截，**与 Tomcat 的 Valve 不同**——Valve 在更底层，在请求到达 Servlet 之前就执行。

### 8.4 完整请求链路（Spring Boot）

```
浏览器
  ↓ HTTP 请求
Tomcat Connector（NIO Endpoint → HTTP11Processor → CoyoteAdapter）
  ↓ HttpServletRequest
Tomcat Container Pipeline（Engine → Host → Context → Wrapper Valve 链）
  ↓
DispatcherServlet.service()  ← Spring 拦截器在这里前后插入
  ↓
HandlerMapping → 找到 @RestController 方法
  ↓
HandlerAdapter → 调用方法，@Valid 参数校验
  ↓
业务代码执行（Service → Mapper → DB）
  ↓
HttpMessageConverter → JSON 序列化
  ↓
写入 Response → Tomcat 发回客户端
```

---

## 九、独立 Tomcat vs Spring Boot 内置 Tomcat 对比

| 维度 | 独立 Tomcat（传统） | Spring Boot 内置 Tomcat |
|------|-------------------|------------------------|
| **谁先启动** | Tomcat 先启动，再加载 Spring | Spring 先启动，再创建 Tomcat |
| **部署方式** | WAR 包放 webapps/ | 直接 `java -jar app.jar` |
| **配置方式** | web.xml + server.xml | application.yaml + @Configuration |
| **DispatcherServlet 注册** | web.xml 手动配 `<servlet-mapping>` | `DispatcherServletAutoConfiguration` 自动注册 |
| **生命周期归属** | Tomcat 管理 Spring Context | Spring 管理内嵌 Tomcat |
| **端口配置** | server.xml 的 `<Connector port="8080">` | `server.port=8080` |
| **多应用支持** | 一个 Tomcat 可部署多个 WAR（多 Context）| 一个进程一个应用（专注单应用） |
| **请求处理路径** | **完全相同**：Connector→Pipeline→Servlet | **完全相同**：Connector→Pipeline→Servlet |
| **运维复杂度** | 需单独管理 Tomcat 进程、日志、配置 | 打包即可运行，云原生友好 |

**最关键的区别**：网络层和请求处理层（Connector → Pipeline → Servlet）**两种方式完全一样**，Spring Boot 只是把 Tomcat 的创建和管理内化到了 Spring 应用启动过程中。你在项目里 CLAUDE.md 总结的请求生命周期（SaInterceptor → Controller → GlobalExceptionHandler）**都发生在 DispatcherServlet 内部**，不受"内置还是外置"影响。

**类比理解**：
- 传统方式：Tomcat 是大楼，Spring 应用是租户，大楼先建好，租户搬进去住
- Spring Boot：Spring 应用是核心，Tomcat 是它随身携带的"便携大楼"，应用走到哪大楼跟到哪

---

## 十、关键概念速查

| 概念 | 一句话说明 |
|------|-----------|
| Catalina | Tomcat 的核心组件，管理整个 Server 生命周期，解析 server.xml |
| Coyote | Tomcat 的连接器实现，负责网络通信 |
| Jasper | Tomcat 的 JSP 编译引擎，现代项目基本不用 |
| Endpoint | 连接器中监听 Socket 的组件（含 Acceptor + SocketProcessor）|
| Processor | 解析 HTTP 报文，产出 Coyote Request 对象 |
| CoyoteAdapter | 适配器，将 Coyote Request 转为 Servlet 规范的 HttpServletRequest |
| Pipeline-Valve | 责任链实现，每层容器有一条管道，Valve 是管道中的处理单元 |
| BasicValve | 每条 Pipeline 的最后一个 Valve，负责调用下一层容器 |
| Mapper | 启动时构建的 URL → 容器映射表，请求路由查找在此完成 |
| Lifecycle | Tomcat 所有组件实现的生命周期接口（init/start/stop/destroy）|
| DispatcherServlet | Spring MVC 前端控制器，Tomcat Pipeline 链路的终点，内部是 Spring 的世界 |
| Servlet 规范 | Tomcat 与 Spring MVC 之间的契约接口，DispatcherServlet 实现它，Tomcat 认识它 |
| Wrapper | Tomcat 对单个 Servlet 的封装，一个 Wrapper = 一个 Servlet |
| HandlerMapping | 根据 URL 找 Controller 方法（Spring MVC 内部，Tomcat 不感知）|
| HandlerAdapter | 调用 Controller 方法，处理参数绑定/校验 |
| HttpMessageConverter | 序列化返回值（@ResponseBody → JSON）|
