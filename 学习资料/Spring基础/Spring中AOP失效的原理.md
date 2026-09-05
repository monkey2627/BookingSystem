# Spring AOP 失效 & @Transactional 事务失效
> 底层根源一致：**Spring AOP是动态代理实现，只有走代理对象调用，切面/事务增强才会生效；直接调用目标类原生对象，增强逻辑不会执行**。
> `@Transactional`本质就是AOP切面，事务通知。

## 一、AOP失效常见原因
Spring AOP默认：**JDK动态代理（实现接口） / CGLIB代理（类继承）**。
代理对象包裹目标对象，所有切面逻辑写在代理类方法里。
Spring容器在创建bean的时候，检测到@Aspect注解(开启切面)或者@Transaction注解，就会根据当前类创造一个代理对象，然后把这个代理对象注入到容器中
在代码里的其他地方如果注入的话，注入的就是容器里的这个代理对象，代理对象的内部含有原生的对象

### 1. 同类内部调用（最常见）
同一个类中，一个方法直接调用本类另一个被`@AOP注解/@Transactional`修饰的方法。
```java
@Service
public class UserService {

    public void test() {
        // ❗ this.addUser()，this是原始目标对象，不是代理对象，切面完全不走
        this.addUser();
    }

    @Transactional
    public void addUser() {
        //数据库操作
    }
}
```
原因：`this`是当前实例，不是Spring容器生成的代理对象，绕过代理，直接执行原方法，AOP增强代码不会执行。

✅ 解决方案
1. 自己注入自己，拿到代理对象调用
```java
@Service
public class UserService {
    @Autowired
    private UserService self; //拿到代理对象

    public void test() {
        self.addUser(); //代理对象调用，AOP生效
    }

    @Transactional
    public void addUser() {}
}
```
2. 从上下文获取 `AopContext.currentProxy()` 获取代理对象；需要开启 `@EnableAspectJAutoProxy(exposeProxy = true)`
```java
((UserService)AopContext.currentProxy()).addUser();
```
3. 将被切面增强的方法拆分到另外一个Service类，跨类调用。

### 2. 方法不是 public
- Spring AOP（JDK/CGLIB）**只能增强public方法**
- private / protected / default 方法不会生成代理增强，注解直接无效。
```java
@Transactional
void addUser(){ // default权限，事务失效
}
```
> 注意：即使CGLIB继承代理，也不能覆写private方法。private方法不会被拦截。

### 3. 类没有交给Spring容器管理
类没有加`@Service/@Component`，没有放入Spring IoC容器。Spring不会为它创建代理对象。直接new出来的对象，AOP完全无效。
```java
// ❌ new出来，不受Spring管理，没有代理
UserService service = new UserService();
```

### 4. @EnableAspectJAutoProxy 配置问题
- SpringBoot默认开启AOP；如果是Spring纯注解项目，缺少`@EnableAspectJAutoProxy`，切面不生效。
- proxyTargetClass配置：
    - `proxyTargetClass=true`：强制CGLIB代理；false优先JDK动态代理。
  > JDK动态代理：**必须实现接口**；如果类没有实现接口，JDK代理无法工作，AOP失效，需要打开CGLIB。

### 5. 切面切点表达式匹配不到目标方法
切点表达式写错，没有匹配上目标方法，通知不会执行。
```java
@Pointcut("execution(* com.dyh.service.*.*(..))") //包名写错，匹配不到
```

### 6. final/static方法
- `final`：CGLIB通过继承重写方法实现增强；final方法不能重写，无法拦截，AOP失效。
- `static`：静态方法不属于对象实例，代理对象无法重写static方法，AOP注解无效。

### 7. 异常被内部catch吃掉（针对事务回滚）
> 属于**事务失效专属**，AOP普通切面不受影响
`@Transactional` 默认只在抛出 **RuntimeException / Error** 才回滚。
如果方法内部try‑catch捕获异常不向外抛出，切面捕获不到异常，不会触发回滚。
```java
@Transactional
public void add(){
    try{
        //数据库报错
    }catch (Exception e){
        //吃掉异常，不throw；事务不会回滚！
    }
}
```
修复：catch之后重新抛出异常，或者指定回滚异常类型
```java
@Transactional(rollbackFor = Exception.class)
```

---

# @Transactional 额外失效场景（除上面AOP共性问题）
1. **数据库引擎不支持事务**：MyISAM引擎没有事务，必须使用InnoDB。
2. 多线程场景：事务绑定当前线程，新线程中操作不会继承当前事务。
3. 传播行为配置错误
   例如 `@Transactional(propagation = Propagation.SUPPORTS)`，非事务环境运行就不会开启事务；
   `Propagation.NOT_SUPPORTED` 会挂起事务，以非事务运行。
4. 本身已经处于事务，异常发生在子方法，但异常被捕获，外层没有感知，不回滚。
5. 只读事务 `readOnly=true`，执行写操作抛异常。
6. 同一个事务中，捕获检查型Exception，没有配置`rollbackFor`，不会回滚。
> 默认只回滚：RuntimeException、Error；普通受检Exception不会回滚。

---

# JDK代理 vs CGLIB代理小结（面试高频）
1. **JDK动态代理**：基于接口；代理类实现目标所有接口；只能拦截接口定义public方法。目标类没有实现接口，无法代理。
2. **CGLIB代理**：继承目标类生成子类；重写非final方法；不需要接口；final方法无法增强。
> SpringBoot2.x之后默认：如果目标实现接口用JDK，否则CGLIB；可以通过`@EnableAspectJAutoProxy(proxyTargetClass=true)`强制全部CGLIB。

---

# 快速排查思路
1. 是否`this`内部调用？打印对象，看是不是代理类（类名带`$$SpringCGLIB$$`）
2. 方法是否public？是否final/static/private？
3. 对象是不是Spring IoC容器Bean，不是new出来的？
4. 切点表达式是否匹配方法？
5. 事务场景：异常是否抛出，是否InnoDB引擎，rollbackFor配置是否正确。

## 高频面试题：为什么this调用会让@Transactional失效？
> `@Transactional`的事务开启、提交、回滚全部写在Spring生成的代理对象方法里面。`this`是原始目标对象，绕开代理直接执行业务方法，完全跳过事务增强逻辑，所以事务不会生效。

如果你需要，我可以写一份最小复现代码演示内部调用事务失效。