# Java代理模式 & Spring AOP 完整深度总结（含底层字节码、反射机制、AOP通知参数原理、全部对话知识点整合）
## 一、代理模式基础
### 1. 定义
代理模式属于**结构型设计模式**。
思想：为**真实目标对象（Target）**提供一个**代理对象（Proxy）**，客户端不再直接访问目标对象，所有请求经过代理转发；
核心价值：**不修改目标类源代码的前提下，在方法调用前后扩展横切逻辑**（日志、权限校验、事务、耗时统计、限流、缓存）。

### 2. 标准三大角色
1. **抽象主题（Subject）**：接口，定义目标需要实现的行为规范；
2. **真实主题（RealSubject / Target）**：原始业务对象，实现核心业务逻辑；
3. **代理（Proxy）**：持有目标对象引用，控制访问，追加增强逻辑。

### 3. 代理三大分类
静态代理、JDK动态代理、CGLIB动态代理

---
## 二、静态代理
### 1. 实现方式
程序员**手动编写代理.java源代码**，编译阶段直接生成独立代理Class文件。
代理类与目标类**实现同一个抽象接口**。
### 2. 代码结构特征
```java
public class StarProxy implements Star {
    //持有目标引用
    private Star target;
    public StarProxy(Star target){this.target = target;}

    @Override
    public void sing() {
        //自定义前置增强
        target.sing();
        //自定义后置增强
    }
    @Override
    public void dance() {
        //自定义前置增强
        target.dance();
    }
}
```
### 3. 底层特点
编译期代码全部固定，不存在运行时动态生成字节码；
接口每增加一个方法，代理类必须手动新增对应实现。

### 4. 优缺点
✅ 优点：实现简单、编译期类型校验、调试方便
❌ 致命缺陷：代码冗余、类爆炸；接口变更时，所有代理类必须同步修改；工程开发极少使用。

---
## 三、JDK动态代理（重点，底层字节码细节）
### 1. 使用硬性约束
**目标类必须实现至少一个接口**
底层原因：JDK自动生成的`$ProxyN`代理类**只能实现接口，不能继承目标类**；Java语法不支持多继承，设计者选择实现接口方案。

### 2. 核心API
```java
public static Object Proxy.newProxyInstance(ClassLoader loader,
                                      Class<?>[] interfaces,
                                      InvocationHandler h)
```
- ClassLoader：类加载器，用来加载动态生成的代理字节码；
- interfaces：代理需要实现的接口数组；
- InvocationHandler：回调处理器，所有方法调用的统一拦截入口。

回调接口：
```java
public interface InvocationHandler {
    Object invoke(Object proxy, Method method, Object[] args) throws Throwable;
}
```
#### 参数深度解析
1. `proxy`：**JDK自动生成的代理实例**，由内存中`$Proxy`类自动传入，程序员不需要手动传递；
   ⚠️ 致命坑：禁止 `method.invoke(proxy, args)`，会再次调用代理方法，无限递归、栈溢出；只能执行 `method.invoke(target, args)`
2. `method`：当前触发调用方法的反射Method对象；
3. `args`：调用方法传入的参数数组，无参数时为`null`。

### 3. 底层执行完整流程
1. `ProxyGenerator.generateProxyClass()` 生成代理类字节码byte[]；
2. ClassLoader将字节码加载到JVM，创建Class对象；
3. 通过反射调用代理类构造器，传入`InvocationHandler`实例；
4. 返回代理对象给调用方；
5. 客户端调用`proxy.sing()`，进入`$Proxy0`内部sing方法；
6. `$Proxy0`内部硬编码调用 `h.invoke(this,method,args)`，转发至我们实现的invoke方法。

> 查看生成的代理Class文件JVM启动参数：
> `-Dsun.misc.proxy.ProxyGenerator.saveGeneratedFiles=true`

### 4. $Proxy0 自动生成类伪代码
```java
public final class $Proxy0 implements Star {
    private final InvocationHandler h;
    public $Proxy0(InvocationHandler h) { this.h = h; }

    @Override
    public void sing(String name) {
        h.invoke(this, singMethod, new Object[]{name});
    }
    @Override
    public void dance() {
        h.invoke(this, danceMethod, new Object[]{});
    }
}
```
✅ 关键结论：**同一个代理对象、同一个Handler可以拦截接口中全部方法**；所有方法统一路由到invoke，依靠Method区分调用函数，并不是只能拦截单个方法。

### 5. InvocationHandler三种实现方式底层对比
#### 方式1：独立具名类 MyHandler
单独创建Java类，编译生成独立class；构造方法手动接收并保存target；适合多处复用。
#### 方式2：匿名内部类
```java
new InvocationHandler(){
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        return method.invoke(bigStar, args);
    }
}
```
编译生成外部类`$1.class`；编译器自动捕获外部局部变量，自动生成构造器传入target。
#### 方式3：Lambda表达式（JDK8+）
```java
(proxy, method, args) -> method.invoke(bigStar, args);
```
❌ **不是匿名内部类语法糖！**
底层依靠`invokedynamic`指令实现，不会生成额外Class文件。

#### 共性约束
匿名内部类、Lambda捕获外部局部变量时，变量必须是`final / effectively final（有效final）`。
底层原理：编译器对外部变量进行值拷贝保存在内部，若外部可以修改引用，会造成内外数据不一致。

#### 重要差异
匿名内部类中的`this` = 匿名类实例；
Lambda表达式中的`this` = 外层宿主类实例。

---
## 四、CGLIB动态代理
### 1. 诞生目的
弥补JDK动态代理短板：**代理没有实现任何接口的普通Java类**。
### 2. 底层原理
基于ASM字节码操作框架，运行时动态生成**目标类的子类**作为代理对象；重写父类所有非final实例方法，实现方法拦截。
### 3. 生成代理类伪代码
```java
public class BigStar$$EnhancerByCGLIB extends BigStar{
    private MethodInterceptor interceptor;
    @Override
    public void sing(){
        interceptor.intercept(this, singMethod, args, methodProxy);
    }
}
```
### 4. 核心API
- `Enhancer`：用来配置父类、回调，创建代理对象；
- `MethodInterceptor.intercept()`：拦截回调；
  执行原始目标方法：`methodProxy.invokeSuper(obj, args)`。

### 5. 硬性底层限制
1. 不能代理`final`修饰的类：无法被继承；
2. 不能拦截`final`修饰的方法：子类无法重写；
3. 不能拦截私有方法。

## 五、JDK动态代理 VS CGLIB动态代理对照表
|对比维度|JDK动态代理|CGLIB动态代理|
|---|---|---|
|字节码生成工具|ProxyGenerator|ASM字节码框架|
|代理实现形式|生成实现目标接口的类|生成目标类的子类|
|回调接口|InvocationHandler|MethodInterceptor|
|原生依赖|JDK自带，无需额外包|早期需要引入第三方依赖|
|使用约束|目标类必须实现接口|目标类不能是final类|
|调用原始方法|method.invoke(target,args)|methodProxy.invokeSuper(obj,args)|

---
## 六、Spring AOP（面向切面编程）深度解析
### 1. 概念区分
1. **AOP是编程思想**：将项目中通用横切逻辑（日志、事务、异常处理）和业务代码解耦；
2. **动态代理是SpringAOP默认运行时实现技术**；
3. 重要区分：
    - Spring AOP：**运行时织入**，依靠动态代理；
    - AspectJ：编译期/类加载期织入，直接修改源码字节码，**不依赖代理**。

### 2. Spring AOP代理选择策略
1. 目标类实现接口 → 默认启用**JDK动态代理**
2. 目标类没有实现任何接口 → 自动切换**CGLIB代理**
3. 全局强制使用CGLIB配置
```yaml
spring:
  aop:
    proxy-target-class: true
```

### 3. AOP核心术语
1. **Target**：原始目标对象
2. **Proxy**：Spring容器生成的代理对象
3. **JoinPoint（连接点）**：所有可以被拦截的点（类中非static、非private实例方法）
4. **Pointcut（切入点）**：匹配规则，筛选需要拦截的方法
5. **Advice（通知）**：需要执行的增强逻辑
6. **Aspect（切面）** = Pointcut + Advice
7. **Weaving（织入）**：将切面增强逻辑嵌入目标方法的过程

### 4. 五大通知类型 + 参数底层原理（重点新增内容）
#### 底层核心机制
Spring容器**启动阶段**利用反射扫描所有`@Aspect`切面类，解析每一个通知方法的**方法签名、参数列表**并缓存；
当方法触发拦截时，读取预先缓存的签名信息，**按需组装参数数组，反射调用通知方法**。
> 规则：Spring不会强行注入参数；方法声明了什么类型，才传入对应对象；声明未知参数直接启动报错。

#### 对象区分
1. `JoinPoint`：代表当前拦截的连接点；可以获取方法名、入参、目标对象；**没有执行目标方法的能力**；适用于除环绕外所有通知。
2. `ProceedingJoinPoint extends JoinPoint`：新增`proceed()`方法；**仅限@Around使用**。

#### 逐个通知详解
1. **@Before 前置通知**
   执行时机：目标方法运行之前；
   参数：`JoinPoint` **可选**，可以无参；
2. **@After 最终通知**
   执行时机：无论方法正常执行还是抛出异常，一定会执行（类似finally）；
   参数：`JoinPoint` **可选**；
3. **@AfterReturning 返回通知**
   执行时机：方法正常执行完成，抛出异常不会触发；
   参数形式：无参 / `JoinPoint` / `JoinPoint + 返回值`；
   需要配置`returning="变量名"`绑定返回值；
4. **@AfterThrowing 异常通知**
   执行时机：目标方法抛出异常时触发；
   参数形式：无参 / `JoinPoint` / `JoinPoint + 异常对象`；
   需要配置`throwing="变量名"`绑定异常；
5. **@Around 环绕通知【最重要，对应JDK代理invoke】**
   执行时机：包裹目标方法，全权控制目标方法执行；
   参数约束：**强制必须声明ProceedingJoinPoint**
   > 底层原因：只有`ProceedingJoinPoint`具备`proceed()`，等价 `method.invoke(target,args)`，用来执行原始业务方法；缺少该参数项目启动直接报错。
   ⚠️ 禁止在其他通知中使用`ProceedingJoinPoint`，启动异常。

#### 通知执行顺序（无异常场景）
环绕前置逻辑 → @Before → 执行业务方法 → @AfterReturning → @After → 环绕后置逻辑
#### 通知执行顺序（抛出异常场景）
环绕前置逻辑 → @Before → 业务方法抛出异常 → @AfterThrowing → @After；环绕后置代码不再执行。

### 5. AOP经典失效场景 & 底层原因
1. **同类内部 this.方法() 调用**
   `this`指向原始目标对象，方法调用不经过代理对象，拦截逻辑无法触发；
   解决方案：从Spring容器获取代理对象进行调用。
2. **private / static / final 方法无法拦截**
   动态代理只能拦截**可重写的public实例方法**；私有方法不能重写、静态方法归属类而非实例。

### 6. AOP与动态代理底层链路打通
1. Spring启动扫描`@Aspect`切面，解析切入点、通知；
2. 实例化原始目标Bean；
3. 根据目标类是否实现接口，选择JDK/CGLIB生成代理对象；
4. 将所有通知逻辑封装进代理的拦截回调；
5. Spring容器保存**代理对象**，对外注入；
6. 外部调用代理对象方法 → 触发代理拦截器；
7. 进入AOP责任链，读取通知方法签名，反射执行各类通知；
8. 环绕通知调用`pjp.proceed()`，执行原始目标业务方法。

> 映射关系：
> `ProceedingJoinPoint.proceed()` ⇔ JDK动态代理 `method.invoke(target, args)`

## 七、全量高频易错点汇总（整合全部对话疑问）
1. JDK代理invoke中的proxy参数由代理类自动传入，禁止用来反射调用，引发无限递归；
2. Lambda、匿名内部类捕获外部target，底层是变量拷贝，强制有效final；
3. 单个代理对象可以拦截接口全部方法，不是只能拦截一个函数；
4. Lambda表达式不等同匿名内部类，底层指令、this指向存在明显区别；
5. AOP是编程思想，动态代理是主流实现手段，二者不能划等号；
6. JDK动态代理生成的`$Proxy`对象，只能强转为接口类型，不能强转为目标实现类；
7. 除环绕通知外，其余四类通知`JoinPoint`参数可选；环绕通知参数强制必填；
8. Spring启动预解析通知方法签名并缓存，运行时动态匹配参数，不是运行时猜测参数；
9. CGLIB无法代理final类、final方法；
10. 所有动态代理只能拦截实例方法，无法拦截静态、私有方法；
11. 匿名内部类、Lambda都不能直接访问外部非有效final局部变量。

## 八、通用完整执行链路
创建目标对象 → 编写拦截增强逻辑 → 运行时动态生成代理字节码并加载 → 获取代理对象
外部调用代理方法 → 进入统一拦截回调(invoke/intercept) → 执行增强逻辑 → 反射调用原始目标方法

SpringAOP：封装上述整套动态代理流程，搭配切入点表达式、多类型通知，实现标准化切面编程。

如果你需要，我可以分出两份文档：
① 完整版学习文档（当前这份）
② 面试精简背诵版，去除解释，直接用于笔试面试作答。