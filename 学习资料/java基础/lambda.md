# Java Lambda 为什么不一定生成新类，和匿名内部类对比
> 重点：**Lambda 不是完全不生成类，而是不强制生成独立 `.class` 文件；匿名内部类一定生成独立的 class 文件**。
> 底层依靠 **invokedynamic + LambdaMetafactory**，运行时动态生成内部类字节码，不是编译期就输出 `.class`。

## 1. 匿名内部类（编译期就产出class）
```java
Runnable r = new Runnable() {
    @Override
    public void run() {
        System.out.println("hello");
    }
};
```
编译后会生成：`Outer$1.class`，**编译阶段就产生独立class文件**。
不管这个匿名类写多少遍，每一处 `new 接口(){...}` 都产生新class。

特点：
1. 编译期生成独立class磁盘文件
2. 持有外部类this引用，可以捕获外部变量
3. 每次new，都是全新的类对象

## 2. Lambda表达式（编译期不生成class文件）
```java
Runnable r = () -> System.out.println("hello");
```
编译后，**不会多出 `Outer$xxx.class` 文件**。
字节码里面只有一条：
```
invokedynamic #0:run,  BootstrapMethods #1
```
- 编译阶段：把lambda的方法体抽成**私有静态/实例方法**放在当前类里面（`lambda$xxx$0()`）
- **真正的代理类字节码，在JVM运行时，由LambdaMetafactory动态生成在内存，不落到磁盘**。

> ⚠️ 误区：“lambda不会生成新的类” → 这句话不准确。
> **运行时内存里确实会生成匿名实现类，只是编译期不输出class文件到磁盘。**

### 什么时候运行时会复用 / 什么时候生成新类？
1. **无捕获（不捕获外部变量）**
```java
Runnable r1 = ()->{};
Runnable r2 = ()->{};
```
JVM会**缓存这个动态生成的lambda代理类实例**，`r1 == r2` 有可能为true，复用同一个对象。

2. **有捕获（捕获局部变量、this）**
```java
int a = 10;
Runnable r1 = ()->System.out.println(a);
Runnable r2 = ()->System.out.println(a);
```
每次执行invokedynamic，都会生成**新的对象实例**，但底层的实现类字节码仍然可以复用，只是对象实例不同。

> 类可以复用，对象实例看是否捕获变量。

## 3. 只有Lambda是这样吗？
**只有 `invokedynamic` 机制的lambda才是这套逻辑。**
- ✅ Lambda表达式：`invokedynamic`，运行时动态生成实现类，编译期无class文件
- ❌ 匿名内部类：普通`new`指令，编译期输出独立class文件
- ❌ 普通内部类、静态内部类：编译期全部生成独立`Outer$X.class`

> 补充：Java 7之前没有invokedynamic，lambda根本实现不了；这个字节码指令本来是给动态语言（Groovy）设计的，Java8拿来实现lambda。

## 4. Lambda 编译后的真实类结构举例
源文件：
```java
public class Demo {
    public void test(){
        Runnable run = ()->{
            System.out.println("test");
        };
    }
}
```
编译后 Demo.class 内部多出一个私有方法：
```java
private static void lambda$test$0() {
    System.out.println("test");
}
```
这个方法**就在Demo类内部**，没有单独class。
运行时 `LambdaMetafactory` 动态造一个实现Runnable的类，把这个方法作为实现。

## 5. Lambda 和匿名内部类关键对比表

|特性|匿名内部类|Lambda表达式|
|---|---|---|
|编译产物|生成`Outer$N.class`磁盘文件|编译期无额外class；类字节码运行时内存生成|
|字节码指令|`new` + `invokespecial`|`invokedynamic`|
|外部this捕获|总是捕获外部this|捕获this与否看是否用到this|
|无捕获场景|每次new新对象|可缓存复用对象实例|
|方法体存放|放在生成的匿名class中|方法体作为私有方法存宿主类|

## 6. 容易踩坑的点
1. Lambda**不是没有类**，只是类不在磁盘，存在JVM内存；可以通过 `-Djdk.lambda.classes.dump=true` 参数，把运行时生成的lambda字节码dump到磁盘，就能看到生成的class。
2. 匿名内部类的`this`是匿名类自己；lambda的`this`等价于外层方法的this。
3. 就算是lambda，**每一处不同的lambda代码点，会生成不同的私有辅助方法**，运行时可以生成不同的实现类。

### 一句话总结
> Lambda编译期不会输出独立class文件，依靠`invokedynamic`，运行时在内存动态生成实现类；
> 匿名内部类编译期直接输出class文件。**不是完全不生成类，只是生成时机和位置不一样，只有lambda用这套invokedynamic机制。**
# LambdaMetafactory 到底做了什么
先回顾编译阶段做的事：
```java
public void test(){
    Runnable run = ()->{
        System.out.println("test");
    };
}
```
javac编译之后：
1. 把lambda里面的代码抽出来，生成宿主类里面的**私有静态辅助方法**
```java
// 在 Demo 类内部，编译器自动生成
private static void lambda$test$0() {
    System.out.println("test");
}
```
2. 原来的lambda表达式位置，不new任何类，只留下一条字节码：`invokedynamic`
> `invokedynamic`：第一次执行的时候，去调用**引导方法BootstrapMethod**，也就是 `LambdaMetafactory.metafactory()`

**`LambdaMetafactory.metafactory()` 就是引导方法，在运行时干活。**

---

## 通俗翻译：动态造一个实现Runnable的类，把这个方法作为实现
> 手写等价版本（只是模拟，这个类不会出现在磁盘上，内存中临时生成）
```java
// 这是运行时JVM内存动态生成出来的类，没有xxx.class文件
final class $$Lambda$123 implements Runnable {

    // 如果lambda捕获外部变量，这里会多几个成员字段保存捕获的值
    public $$Lambda$123() {
    }

    @Override
    public void run() {
        // 【重点】不把业务代码再复制一份！直接调用宿主类提前抽好的辅助方法
        Demo.lambda$test$0();
    }
}
```
然后 new 这个内存里的 `$$Lambda$123` 对象，赋值给 `Runnable run`。

拆解这句话三层含义：
1. **动态造实现Runnable的类**
   JVM在内存生成字节码，造一个实现了函数式接口（Runnable）的类，这个类只存在内存，默认不写到磁盘。类名类似 `$$Lambda$1/0x00000008000a1234`。

2. **不重新写一遍lambda的业务逻辑**
   lambda的逻辑已经被javac抽到宿主类 `lambda$test$0()`，**动态生成的代理类不会重复存这份代码**。

3. **把这个辅助方法作为接口方法的实现**
   动态类重写的`run()`方法里面，不去写`System.out.println("test")`，而是直接**调用宿主类的那个lambda$xxx辅助方法**。

> 动态生成的lambda类，本质就是一层薄薄的壳，专门用来实现接口，真正业务逻辑还在原来的宿主类中。

---

## 分两种场景：无捕获 vs 捕获变量
### 场景1：无捕获（上面例子）
内存生成的lambda类：
- 无成员变量
- run直接调用宿主静态辅助方法
- JVM可以缓存这个类的实例，多次调用可以返回同一个对象

### 场景2：捕获局部变量
```java
int num = 10;
Runnable r = () -> System.out.println(num);
```
1. javac：生成辅助方法，把捕获的变量作为方法参数
```java
private static void lambda$test$1(int num) {
    System.out.println(num);
}
```
2. LambdaMetafactory运行时造类，**增加成员字段保存捕获的值**
```java
final class $$Lambda$456 implements Runnable {
    private final int arg$1;

    public $$Lambda$456(int arg$1) {
        this.arg$1 = arg$1;
    }

    @Override
    public void run() {
        // 把成员字段传给编译器生成的辅助方法
        Demo.lambda$test$1(this.arg$1);
    }
}
```
每次执行invokedynamic，就 new `$$Lambda$456(10)`，把num传进去。
> 类的字节码可以复用，但是每一次执行都会new新对象。

---

## 和匿名内部类对比，看清差别
匿名内部类编译期就生成`Demo$1.class`磁盘文件：
```java
class Demo$1 implements Runnable{
    @Override
    public void run() {
        // 业务逻辑直接写在这个类的run方法里面
        System.out.println("test");
    }
}
```
业务逻辑直接拷贝到匿名类内部。

而Lambda：
- 业务代码留在**宿主类**
- 动态生成的lambda类只是一层转发壳，run方法内部就一句方法调用。

---

## invokedynamic完整流程梳理
1. **编译期 javac**
    - 提取lambda体 → 在宿主类生成私有辅助方法 `lambda$xxx$N`
    - lambda位置生成字节码 `invokedynamic`，记录引导方法是`LambdaMetafactory.metafactory`，记录：接口类型Runnable、要实现的抽象方法run、要调用的辅助方法句柄。
2. **第一次运行到这条invokedynamic字节码**
    - JVM执行引导方法：`LambdaMetafactory.metafactory(...)`
    - metafactory内部：ASM动态在内存生成字节码，造出实现Runnable的lambda类
    - 实例化这个动态类对象，返回
    - invokedynamic会缓存这个调用点，后续再次执行直接拿已经生成好的类/对象，不再重复跑metafactory。
3. **调用run()**
    - 执行动态lambda类的run() → 转发调用宿主类的`lambda$test$0()`，执行业务。

> 参数 `-Djdk.lambda.classes.dump=true` 开启dump，JVM会把内存生成的lambda class输出成文件，可以反编译看到上面模拟的`$$Lambda$xxx`真实代码。

## 关键疑问点
> Q：LambdaMetafactory是JDK的工具类，是谁调用它？
不是你的代码直接调用，是**invokedynamic字节码指令触发，JVM自动调用引导方法LambdaMetafactory.metafactory**。你写的源码看不到这个调用。

> Q：那是不是每次执行都生成新类？
不是。同一个invokedynamic调用点（源码同一个lambda位置），引导方法**只会执行一次**，生成一次lambda类，后续复用。
不同源码位置的lambda表达式，对应不同invokedynamic调用点，会生成不同的动态lambda类。

