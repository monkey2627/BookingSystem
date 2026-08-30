# 作用
* 基于project object model项目对象模型对java项目进行管理
## 依赖管理
maven项目都有一个pom.xml配置文件，通过在配置文件中配置自己想要使用的依赖
maven就会帮助我们自动下载这些依赖放进项目中
## 统一项目结构
只要是maven项目就有一个标准的项目目录结构，所以无论用哪个开发工具都可以直接导入
\
根目录\
--src\
---main:存放实际的项目资源\
----java：存放java源代码目录\
----resources:存配置文件目录\
---test：测试项目资源\
---pom.xml:maven项目配置文件
## 项目构建
提供项目的清理(清理上次的构建残留)、编译、测试、打包、发布(将打包好的jar包安装到本地仓库)自动工具
# maven中的名词
* 坐标：可以唯一的标识一个项目资源：由groupId，artifactId，version组成
* 中央仓库：官方维护的jar包仓库
* 本地仓库：用户计算机上的jar包仓库，先找本地再找远程再找中央
* 远程仓库：不同于官方的私有仓库
# 分模块开发
* 将原始模块按照功能拆分成若干个子模块
* pom里面引用的模块一定要在本地仓库能够找到，一个自己的模块引用另一个自己的模块，要把模块install到本地仓库
    <modules>
        <module>mhp-common</module>
        <module>mhp-gateway</module>
        <module>mhp-app</module>
    </modules>

# 依赖传递、排除依赖、隐藏依赖
* 依赖某个资源（直接依赖），这个资源依赖其他东西(间接依赖)，都可以使用，maven会自动帮我们全部下载
* 当同一个依赖出现多次(但版本不同)，层级靠前的优先级更高
* 如果想隐藏自己的依赖，可以用<optional> 配置之后就不再依赖传递给其他项目
* 排除依赖：断开直接依赖的某个间接依赖，用于不需要直接依赖中其他依赖的情况，注意和隐藏依赖的区别，一个是被使用方自己屏蔽，一个是使用方排除
* scope可以用来控制依赖的可见范围
# 继承与聚合
## 聚合
* 建立一个空工程，里面只有一个文件pom，打包方式设置为pom
* 在<modules>里面添加所有需要管理的模块名称，在构建的时候会按照依赖的关系进行构建
## 继承
描述两个工程之间的关系，子工程继承父工程的配置信息，继承关系在子类中描述
<parent> 里面打上坐标就行
父工程直接写在<dependency> 里的会被直接继承，dependencymanagement里的可以供选择是否继承，注意这样在子工程中引用的时候就不要写版本了，会用父工程中的版本
`<dependencyManagement>` 用于**集中管理依赖版本**，但**不会实际引入依赖**。它声明在父 POM 中，子模块继承后，在 `<dependencies>` 中引用时可以省略 `<version>`。
# 属性
* 可以理解为pom中的变量
* 写在<properties></properties>里面，名字自己随便取，用的时候${}
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
## 版本管理
SNAPSHOP表不稳定
RELEASE表稳定版本
# 多环境配置与应用
<profiles>
    <profile>
        <id>name </id>
        <properties>（前面学的属性）
        </properties>
        <activation>
            <activeByDefault>
                true 表示默认的环境是当前这个
            </activeByDefault>
        </activation>
    </profile>
 </profiles>

# 跳过测试
 <build>
    <resources>
    <!--设置资源目承，并设置能够解斯$}-->
        <resource>
        <directory>${project.basedir}/src/main/resources</directory><filtering>true</filtering>
        </resource>
    </resources>
    <plugins>
        <plugin>
        <artifactId>maven-surefire-plugin</artifactId><version>2.12.4</version>
            <configuration>
            <skipTests>false</skipTests>  maven的测试阶段是靠测试插件，可以配置跳过测试
            </configuration>
        </plugin>
    </plugins>
</build>
