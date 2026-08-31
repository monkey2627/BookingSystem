# Maven

## 作用
* 基于 Project Object Model 项目对象模型对 Java 项目进行管理

### 依赖管理
Maven 项目都有一个 pom.xml 配置文件，通过在配置文件中配置自己想要使用的依赖，Maven 就会帮助我们自动下载这些依赖放进项目中。

### 统一项目结构
只要是 Maven 项目就有一个标准的项目目录结构，所以无论用哪个开发工具都可以直接导入。

```
根目录
└── src
    ├── main              存放实际的项目资源
    │   ├── java          存放 Java 源代码
    │   └── resources     存放配置文件
    └── test              测试项目资源
pom.xml                   Maven 项目配置文件
```

### 项目构建
提供项目的清理、编译、测试、打包、发布（将打包好的 jar 包安装到本地仓库）自动工具。

---

## Maven 中的名词

* **坐标**：可以唯一标识一个项目资源，由 groupId、artifactId、version 组成
* **中央仓库**：官方维护的 jar 包仓库
* **本地仓库**：用户计算机上的 jar 包仓库，先找本地再找远程再找中央
* **远程仓库**：不同于官方的私有仓库

---

## 分模块开发

* 将原始模块按照功能拆分成若干个子模块
* pom 里面引用的模块一定要在本地仓库能够找到，一个自己的模块引用另一个自己的模块，要把模块 install 到本地仓库

```xml
<modules>
    <module>mhp-common</module>
    <module>mhp-gateway</module>
    <module>mhp-app</module>
</modules>
```

### package vs install（多模块项目关键区别）

| 命令 | 做什么 | JAR 输出位置 |
|------|--------|------------|
| `mvn package` | 编译 + 打包 | 只放在各模块的 `target/` 目录 |
| `mvn install` | 编译 + 打包 + **安装到本地仓库** | `target/` + `~/.m2/repository/` |

**从根目录整体构建时**，Maven 的 Reactor（反应器）分析出所有模块属于同一个多模块项目，按依赖顺序构建，Reactor 直接引用上一个模块的 `target/` 输出，**不需要本地仓库**，所以 `package` 够用。

**断点续跑 `-rf :mhp-account` 时**，Reactor 里没有 mhp-common，只能去本地仓库找，发现没有，报错：

```
Could not find artifact com.mhp:mhp-common:jar:1.0.0 in central
```

**正确做法**：多模块项目统一在父 POM 目录下执行 `install`，每个模块构建完都装进本地仓库，后续无论怎么引用都能找到：

```bash
mvn clean install -DskipTests
```

### Reactor 构建顺序

Maven 根据模块间的 `<dependency>` 关系自动确定构建顺序：

```
mhp-parent（父 POM）
    ↓
mhp-common（无依赖其他子模块）
    ↓
mhp-gateway / mhp-account / mhp-booking / mhp-social（均依赖 mhp-common）
```

### 常用构建命令

```bash
# 全量构建（推荐，从父 POM 目录执行）
mvn clean install -DskipTests

# 只重新打包某一个模块（前提：依赖模块已 install 到本地仓库）
mvn package -pl mhp-social -DskipTests

# 从指定模块断点续跑（前提：之前的模块已 install 到本地仓库）
mvn install -DskipTests -rf :mhp-account
```

---

## 依赖传递、排除依赖、隐藏依赖

* 依赖某个资源（直接依赖），这个资源依赖其他东西（间接依赖），都可以使用，Maven 会自动帮我们全部下载
* 当同一个依赖出现多次（但版本不同），层级靠前的优先级更高
* **隐藏依赖**：`<optional>true</optional>` 配置之后就不再依赖传递给其他项目（被使用方自己屏蔽）
* **排除依赖**：`<exclusions>` 断开直接依赖的某个间接依赖（使用方主动排除）
* `scope` 可以用来控制依赖的可见范围

---

## 继承与聚合

### 聚合
* 建立一个空工程，里面只有一个文件 pom，打包方式设置为 pom
* 在 `<modules>` 里面添加所有需要管理的模块名称，在构建的时候会按照依赖的关系进行构建

### 继承
描述两个工程之间的关系，子工程继承父工程的配置信息，继承关系在子类中描述：

```xml
<parent>
    <!-- 打上父工程坐标即可 -->
</parent>
```

* 父工程直接写在 `<dependency>` 里的会被直接继承
* `<dependencyManagement>` 里的可以供选择是否继承（子工程引用时不写版本，使用父工程中的版本）

```xml
<!-- 父 POM：集中管理版本，不实际引入依赖 -->
<dependencyManagement>
    <dependencies>
        <dependency>...</dependency>
    </dependencies>
</dependencyManagement>
```

---

## 属性（pom 变量）

```xml
<properties>
    <java.version>21</java.version>
    <spring-cloud.version>2023.0.1</spring-cloud.version>
    <spring-cloud-alibaba.version>2023.0.1.0</spring-cloud-alibaba.version>
    <mybatis-plus.version>3.5.5</mybatis-plus.version>
    <sa-token.version>1.38.0</sa-token.version>
    <redisson.version>3.25.0</redisson.version>
    <hutool.version>5.8.25</hutool.version>
    <xxl-job.version>2.4.0</xxl-job.version>
    <qiniu.version>7.14.0</qiniu.version>
    <springdoc.version>2.3.0</springdoc.version>
</properties>
```

使用时：`${java.version}`

---

## 版本管理

* `SNAPSHOT`：不稳定版本，开发阶段用
* `RELEASE`：稳定版本，发布用

---

## 多环境配置

```xml
<profiles>
    <profile>
        <id>dev</id>
        <properties>
            <!-- 环境相关属性 -->
        </properties>
        <activation>
            <activeByDefault>true</activeByDefault>  <!-- 默认激活此环境 -->
        </activation>
    </profile>
</profiles>
```

---

## 跳过测试

```xml
<build>
    <plugins>
        <plugin>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>2.12.4</version>
            <configuration>
                <skipTests>true</skipTests>
            </configuration>
        </plugin>
    </plugins>
</build>
```

命令行方式：`mvn clean install -DskipTests`
