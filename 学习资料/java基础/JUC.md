# Java 并发编程（JUC）

---

## 一、线程基础：Thread / Runnable / Callable / Future

### 1. 核心定义

**Thread（类）**

```java
public class Thread implements Runnable
```

- 真正代表操作系统线程的对象，调用 `start()` 开启新线程；直接调用 `run()` 只是普通方法，不会新建线程。
- 两种用法：继承 Thread 重写 `run()`；或构造时传入 Runnable 对象。

**Runnable（接口，JDK 1.0）**

```java
@FunctionalInterface
public interface Runnable {
    void run();  // 无返回值，不能抛受检异常
}
```

封装线程要执行的业务逻辑，与 Thread 解耦。可传给 `Thread` 构造器或线程池 `execute()`。

**Callable（接口，JDK 1.5）**

```java
@FunctionalInterface
public interface Callable<V> {
    V call() throws Exception;  // 有泛型返回值，可以抛受检异常
}
```

解决 Runnable 不能拿返回值、不能抛异常的痛点。**不能直接传给 Thread**，需要用 FutureTask 包装。

**Future（接口，JDK 1.5）**

```java
public interface Future<V> {
    V get() throws InterruptedException, ExecutionException;  // 阻塞等待结果
    V get(long timeout, TimeUnit unit);  // 带超时的等待
    boolean cancel(boolean mayInterruptIfRunning);
    boolean isCancelled();
    boolean isDone();
}
```

代表异步任务的结果凭证，`get()` 阻塞直到任务完成，任务出错时将异常包装成 `ExecutionException` 抛出。

### 2. FutureTask — 连接 Callable 与 Thread 的桥梁

```
FutureTask → RunnableFuture → Runnable, Future
```

FutureTask 同时实现了 Runnable（可传给 Thread）和 Future（可获取结果），是使用 Callable 的标准方式：

```java
Callable<Integer> callable = () -> 100;
FutureTask<Integer> task = new FutureTask<>(callable);
new Thread(task).start();    // 当作 Runnable 传给 Thread
Integer result = task.get(); // 当作 Future 拿返回值（阻塞）
```

线程池 `submit(callable)` 底层也是帮你封装成 FutureTask 并返回 Future 对象。

### 3. 四者对比

| 类型 | 类型 | 核心方法 | 返回值 | 能抛受检异常 | 能直接给 Thread |
|------|------|----------|--------|-------------|----------------|
| Thread | class | start() / run() | void | - | 本身就是线程 |
| Runnable | interface | run() | void | 否 | 是 |
| Callable | interface | call() | V（泛型） | 是 | 否（需 FutureTask）|
| Future | interface | get() / cancel() | V | get() 会抛 | 否（存结果） |

### 4. 线程池提交方式

```java
executor.execute(runnable);        // 无返回值
Future<?> f1 = executor.submit(runnable);         // Future.get() 返回 null
Future<T>  f2 = executor.submit(callable);         // Future.get() 返回结果
Future<T>  f3 = executor.submit(runnable, result); // Future.get() 返回传入的 result
```

### 5. 常见易错点

- `thread.run()` 不开新线程，必须用 `start()`。
- `new Thread(callable)` 编译报错，Callable 不是 Runnable。
- `future.get()` 会阻塞当前线程，注意设超时避免死锁。
- Runnable 的 run() 不能 throws 异常，只能内部 try-catch；Callable 的 call() 可以 throws Exception。

---

## 二、synchronized 关键字

### 原理：管程（Monitor）

synchronized 的底层实现基于 **管程（Monitor）** 模型。每个 Java 对象都天然关联一个监视器锁（Monitor）：

- **EntryList（入口集）**：等待获取锁的线程在此阻塞
- **WaitSet（等待集）**：调用 `wait()` 后进入等待的线程
- **Owner**：当前持有锁的线程（只能有一个）

字节码层面：同步方法块使用 `monitorenter` / `monitorexit` 指令；同步方法用 `ACC_SYNCHRONIZED` 标志。

### 使用方式

```java
// 1. 修饰实例方法：锁 this（当前对象）
public synchronized void method() { ... }

// 2. 修饰静态方法：锁 Class 对象
public static synchronized void staticMethod() { ... }

// 3. 修饰代码块：锁指定对象
synchronized (lockObject) { ... }
```

### 行为特性

| 特性 | 说明 |
|------|------|
| **互斥** | 同一时刻只有一个线程能持有同一把锁 |
| **可重入** | 同一线程可以反复获取同一把锁（不会自己死锁），底层通过计数器实现 |
| **内存可见性** | 解锁时强制将修改刷回主存；加锁时从主存重新读取，保证可见性 |
| **不可中断** | 等待锁的线程无法被中断（区别于 ReentrantLock） |

### 锁升级（JDK 6+ 优化）

为减少重量级锁的开销，JVM 引入了锁升级机制：

```
无锁 → 偏向锁（同一线程反复获取，无 CAS） → 轻量级锁（多线程交替获取，CAS 自旋） → 重量级锁（有竞争，进入 OS 阻塞）
```

---

## 三、park() / unpark() 与 LockSupport

`LockSupport` 是 JDK 提供的线程阻塞工具类，比 `wait/notify` 更灵活：

```java
// 暂停当前线程（消耗一个"许可"，若无许可则阻塞）
LockSupport.park();

// 恢复指定线程（发放一个"许可"）
LockSupport.unpark(thread);
```

**与 wait/notify 的核心区别：**

| 对比点 | wait/notify | park/unpark |
|--------|-------------|-------------|
| 必须持有锁 | 必须在 synchronized 内 | 不需要持有任何锁 |
| 调用顺序 | notify 必须在 wait 之前调用才有效 | unpark 可先于 park 调用（许可提前发放） |
| 精确唤醒 | notify 随机唤醒一个 | unpark 指定线程唤醒 |

底层基于 `Unsafe.park()` / `Unsafe.unpark()`，AQS 的阻塞/唤醒逻辑均使用此机制实现。

---

## 四、volatile 关键字

volatile 提供两种保证：

**1. 可见性**：对 volatile 变量的写操作立即刷新到主存，读操作直接从主存读取，避免 CPU 缓存导致的可见性问题。

**2. 有序性（禁止指令重排）**：通过内存屏障（Memory Barrier）禁止编译器/CPU 对 volatile 变量读写前后的指令进行重排序。

```java
// 经典：双重检查单例模式
class Singleton {
    private static volatile Singleton instance;  // 必须加 volatile

    public static Singleton getInstance() {
        if (instance == null) {                   // 第一次检查（不加锁）
            synchronized (Singleton.class) {
                if (instance == null) {           // 第二次检查（加锁后）
                    instance = new Singleton();   // 若无 volatile，new 可能被重排
                }
            }
        }
        return instance;
    }
}
```

**volatile 不保证原子性**：`i++` 是三步操作（读→加→写），volatile 无法保证这三步的整体原子性，需要用原子类或 synchronized。

---

## 五、CAS 与原子类

### CAS（Compare And Swap，比较并交换）

CAS 是实现无锁并发的核心操作，包含三个参数：

- **内存位置 V**：要修改的变量地址
- **期望值 A**：认为当前值应该是什么
- **新值 B**：要设置的新值

逻辑：若 V 的当前值等于 A，则将 V 设置为 B，返回成功；否则不修改，返回失败，由调用方决定是否重试。

底层依赖 CPU 指令（x86 的 `cmpxchg`），是真正意义上的原子操作，不需要操作系统介入。

**适用场景**：线程数少、竞争不激烈、多核 CPU，体现乐观锁思想（不上锁，失败就重试）。

**ABA 问题**：变量从 A 改成 B 又改回 A，CAS 无法感知中间发生过修改。解决方案：引入版本号（`AtomicStampedReference`）或标记（`AtomicMarkableReference`）。

### 原子整数

`AtomicBoolean`、`AtomicInteger`、`AtomicLong` — 对单个基本类型值进行原子操作：

```java
AtomicInteger ai = new AtomicInteger(0);
ai.incrementAndGet();           // 原子 +1，返回新值
ai.getAndAdd(5);                // 原子 +5，返回旧值
ai.compareAndSet(0, 100);       // CAS：期望 0，设为 100
```

内部结合 `volatile` + `CAS` 实现：volatile 保证读取最新值，CAS 保证修改的原子性。

### 原子引用

`AtomicReference<T>` — 对对象引用进行原子操作：

```java
AtomicReference<String> ref = new AtomicReference<>("hello");
ref.compareAndSet("hello", "world");  // 期望是 "hello" 才替换
```

存在 ABA 问题，用 `AtomicStampedReference<T>` 解决（带版本号）：

```java
AtomicStampedReference<String> stampedRef =
    new AtomicStampedReference<>("v1", 0);  // 初始值 + 初始版本号
int[] stampHolder = new int[1];
String current = stampedRef.get(stampHolder);  // 同时获取值和版本号
stampedRef.compareAndSet(current, "v2", stampHolder[0], stampHolder[0] + 1);
```

`AtomicMarkableReference<T>` 只关心是否被修改过（用 boolean 标记代替版本号）。

### 原子数组

`AtomicIntegerArray`、`AtomicLongArray`、`AtomicReferenceArray<T>` — 对数组中单个元素进行原子操作（区别于 AtomicReference，它保护的是数组内元素，而非数组引用本身）。

### 字段更新器

`AtomicIntegerFieldUpdater`、`AtomicLongFieldUpdater`、`AtomicReferenceFieldUpdater` — 对已有类的某个字段进行原子操作，无需改动原类：

```java
// 要求：字段必须用 volatile 修饰，且 public（或在同包内）
AtomicIntegerFieldUpdater<User> updater =
    AtomicIntegerFieldUpdater.newUpdater(User.class, "score");
updater.incrementAndGet(userInstance);  // 原子更新 userInstance.score
```

### 原子累加器 LongAdder

专门用于高并发累加场景，性能比 `AtomicLong` 高得多：

**LongAdder 原理**：内部维护一个 `base` 值和多个 `Cell` 累加单元（数组）。低竞争时直接 CAS 更新 base；高竞争时分散到不同 Cell（每个线程哈希到不同 Cell 上），最终 `sum()` = base + ΣCell。

**伪共享问题**：多个 Cell 可能落在同一个 CPU 缓存行（64 字节）上，一个核修改会导致其他核的整行缓存失效，降低性能。`@sun.misc.Contended` 注解通过填充字节让每个 Cell 独占一条缓存行，解决伪共享。

```java
LongAdder adder = new LongAdder();
adder.increment();   // 原子 +1
adder.add(5);        // 原子 +5
long total = adder.sum();  // 汇总所有 Cell（非原子，并发时可能不精确）
```

---

## 六、不可变与线程安全

### 不可变设计

`String`、基本类型包装类（`Integer`、`Long` 等）、`BigDecimal` 等设计为不可变类。不可变类天然线程安全，无需同步。

**实现要点：**
- 类声明 `final`：防止子类覆盖方法破坏不可变约束
- 字段声明 `final`：赋值后引用不可替换，同时 `final` 写入会插入写屏障，保证可见性
- 修改操作返回新对象，不修改原对象

### 享元模式

不可变对象频繁创建会产生大量垃圾，享元模式（Flyweight）通过对象复用减少内存开销：
- `String` 常量池：相同字面量共享同一对象
- `Integer` 缓存：`-128 ~ 127` 范围的整数缓存，`Integer.valueOf(127) == Integer.valueOf(127)` 为 `true`
- 数据库连接池、线程池：复用昂贵对象

### 无状态类

没有任何实例变量的类（如工具类 `Math`、`Collections`），多线程访问时不存在线程安全问题，因为每次调用的数据都在各自的栈帧中。

---

## 七、AQS（AbstractQueuedSynchronizer）

AQS 是 Java 并发包中 **阻塞式锁和相关同步器的基础框架**，ReentrantLock、Semaphore、CountDownLatch 等都基于它实现。

### 核心结构

```
AQS 内部：
  volatile int state          // 同步状态（0=未锁定，≥1=已锁定/重入次数）
  Node head, tail             // 双向 FIFO 等待队列（CLH 队列变体）
  Thread exclusiveOwnerThread // 当前独占锁的线程
```

- **state**：用于表示资源的状态，不同同步器赋予不同语义（ReentrantLock 中 0=未锁定，ReentrantLock 重入时 state 累加）
- **等待队列**：类似管程的 EntryList，阻塞等待锁的线程以 Node 形式加入队列尾部
- **条件变量**：`ConditionObject` 实现，类似管程的 WaitSet，支持 `await()` / `signal()`

### 工作流程（以独占锁为例）

1. 线程 A 调用 `lock()`，CAS 将 state 从 0 改为 1，成功则持有锁
2. 线程 B 调用 `lock()`，CAS 失败，将自身封装成 Node 加入队列，调用 `LockSupport.park()` 阻塞
3. 线程 A 调用 `unlock()`，state 减为 0，调用 `LockSupport.unpark()` 唤醒队列头部线程
4. 线程 B 被唤醒后再次尝试 CAS 获取锁

### 子类需实现的方法

| 方法 | 说明 |
|------|------|
| `tryAcquire(int arg)` | 尝试独占获取锁（CAS 修改 state） |
| `tryRelease(int arg)` | 尝试独占释放锁 |
| `tryAcquireShared(int arg)` | 尝试共享获取锁 |
| `tryReleaseShared(int arg)` | 尝试共享释放锁 |

AQS 提供了排队、阻塞、唤醒的通用框架，子类只需关注 state 的含义和 CAS 逻辑。

---

## 八、ReentrantLock（可重入锁）

### 基本使用

```java
ReentrantLock lock = new ReentrantLock();

lock.lock();               // 阻塞获取锁
try {
    // 临界区
} finally {
    lock.unlock();         // 必须在 finally 中释放，避免异常时死锁
}

// 可中断等待
lock.lockInterruptibly();  // 等待期间可被 interrupt() 中断

// 尝试获取，不阻塞
boolean acquired = lock.tryLock();          // 立即返回
boolean acquired2 = lock.tryLock(3, TimeUnit.SECONDS);  // 最多等 3 秒
```

### 与 synchronized 对比

| 对比点 | synchronized | ReentrantLock |
|--------|-------------|---------------|
| 可中断 | 不可中断 | `lockInterruptibly()` 支持中断 |
| 超时获取 | 不支持 | `tryLock(timeout)` |
| 公平/非公平 | 非公平 | 构造器参数控制 `new ReentrantLock(true)` |
| 条件变量 | 单个 wait/notify | 多个 Condition（`lock.newCondition()`）|
| 释放 | JVM 自动释放 | 必须手动 `unlock()`（在 finally 中）|

### 公平锁 vs 非公平锁

- **非公平锁（默认）**：新来的线程直接尝试 CAS 抢锁，若成功则跳过队列；等待线程有可能被"插队"。吞吐量更高。
- **公平锁**：获取锁前先检查队列是否有等待线程，有则直接入队等待，严格按 FIFO 顺序。减少饥饿，但增加上下文切换开销。

### 可重入原理

同一线程已持有锁时，再次调用 `lock()` 只是将 state 加 1（不会阻塞自己）；`unlock()` 将 state 减 1，降为 0 时才真正释放锁。

### 条件变量（Condition）

ReentrantLock 支持多个条件变量，可以将不同条件的等待线程分组管理，精确唤醒：

```java
ReentrantLock lock = new ReentrantLock();
Condition notFull  = lock.newCondition();  // 队列不满
Condition notEmpty = lock.newCondition();  // 队列非空

// 生产者
lock.lock();
try {
    while (queue.isFull()) notFull.await();  // 满了就等"不满"条件
    queue.add(item);
    notEmpty.signal();  // 精确唤醒消费者
} finally { lock.unlock(); }
```

### 实现原理（非公平锁）

`NonfairSync` 继承自 AQS，内部结构：state（锁状态）、head/tail（等待队列）、exclusiveOwnerThread（持有者）。

- 竞争失败的线程以 Node 形式加入 CLH 双向链表队尾，调用 `park()` 阻塞
- 每个节点有义务在释放时唤醒其后继节点（`unpark`）
- "非公平"体现在：新来的线程先尝试 CAS，若正好在持有者释放的瞬间抢到，被唤醒的队列节点竞争失败，重新阻塞

---

## 九、线程池 ThreadPoolExecutor

### 核心参数

```java
new ThreadPoolExecutor(
    int corePoolSize,        // 核心线程数：长期保留，即使空闲也不销毁
    int maximumPoolSize,     // 最大线程数：包括核心线程 + 救急线程
    long keepAliveTime,      // 救急线程空闲超时时长
    TimeUnit unit,           // 时间单位
    BlockingQueue<Runnable> workQueue,  // 任务队列（核心线程全忙时入队）
    ThreadFactory threadFactory,        // 线程工厂（可自定义线程名等）
    RejectedExecutionHandler handler    // 拒绝策略（队列满且达到最大线程数时触发）
)
```

**线程池状态**（用 int 高 3 位存储，低 29 位存线程数）：

| 状态 | 说明 |
|------|------|
| RUNNING | 正常接受新任务，处理队列任务 |
| SHUTDOWN | 不接受新任务，但处理队列中已有任务（`shutdown()` 触发）|
| STOP | 不接受新任务，不处理队列任务，中断正在执行的线程（`shutdownNow()` 触发）|
| TIDYING | 所有任务已终止，线程数为 0 |
| TERMINATED | `terminated()` 钩子执行完毕 |

### 任务提交流程

```
提交任务
  → 核心线程数未满 → 创建核心线程执行
  → 核心线程已满 → 任务进入队列
  → 队列已满 + 未达最大线程数 → 创建救急线程执行
  → 队列已满 + 达到最大线程数 → 执行拒绝策略
```

**救急线程**：超过 corePoolSize 创建的线程，空闲超过 keepAliveTime 后自动销毁。

### 内置拒绝策略

| 策略 | 行为 |
|------|------|
| `AbortPolicy`（默认） | 抛出 `RejectedExecutionException` |
| `CallerRunsPolicy` | 让提交任务的调用者线程自己执行任务（降速，不丢任务）|
| `DiscardPolicy` | 静默丢弃新任务 |
| `DiscardOldestPolicy` | 丢弃队列头部最旧的任务，重新尝试提交 |

### 常用工厂方法

**`Executors.newFixedThreadPool(n)`**：线程数固定为 n，无救急线程，任务队列无界（`LinkedBlockingQueue`）。适合任务数已知的场景；无界队列可能导致 OOM。

**`Executors.newCachedThreadPool()`**：所有线程都是救急线程（keepAliveTime=60s），队列用 `SynchronousQueue`（容量为 0，必须有线程接收才能放入）。高并发短任务场景效果好；无上限线程数可能 OOM。

**`Executors.newSingleThreadExecutor()`**：线程数固定为 1，任务队列无界。保证任务串行执行，且出错后自动重建线程（区别于自己 new 一个单线程，出错就没有线程了）。

**`Executors.newScheduledThreadPool(n)`**：支持定时和周期性任务：
- `scheduleAtFixedRate(task, delay, period, unit)`：按固定速率，上次**开始**后 period 再次执行（若执行超时，追赶但不重叠）
- `scheduleWithFixedDelay(task, delay, delay, unit)`：上次**结束**后再等 delay 才执行，间隔固定

### 提交任务 API

```java
executor.execute(runnable);                     // 无返回值
Future<?> f1 = executor.submit(runnable);       // Future.get() 返回 null
Future<T>  f2 = executor.submit(callable);       // Future.get() 返回结果
List<Future<T>> futures = executor.invokeAll(callableList);  // 批量提交，等全部完成
T result = executor.invokeAny(callableList);    // 返回最先完成的结果
```

### 关闭线程池

```java
executor.shutdown();     // 软关闭：不接受新任务，等待已提交任务执行完毕
executor.shutdownNow();  // 强关闭：不接受新任务，中断正在执行的任务，返回队列中未执行的任务列表
```

### 正确处理任务异常

```java
// 方式 1：execute() 提交时，在 run() 内 try-catch
executor.execute(() -> {
    try {
        doTask();
    } catch (Exception e) {
        log.error("任务执行失败", e);
    }
});

// 方式 2：submit() 提交，通过 Future.get() 捕获
Future<String> future = executor.submit(() -> doTask());
try {
    future.get();
} catch (ExecutionException e) {
    log.error("任务执行失败", e.getCause());
}
```

### 线程数设置建议

- **CPU 密集型任务**（大量计算）：线程数 = CPU 核心数 + 1，多一个备用应对偶发中断
- **IO 密集型任务**（网络/磁盘等待多）：线程数 = CPU 核心数 × (1 + IO 等待时间 / CPU 计算时间)，通常 2~4 倍核心数
- **实际建议**：压测后根据吞吐量和响应时间调整，不要靠公式硬套

---

## 十、ForkJoin 框架

ForkJoin 是 JDK 7 引入的并行计算框架，适合 **能递归拆分的 CPU 密集型任务**（如大数组求和、归并排序、递归遍历）。

### 核心概念

- **ForkJoinPool**：特殊线程池，内部每个线程维护各自的**双端队列（Deque）**
- **工作窃取（Work Stealing）**：空闲线程从其他线程的 Deque 尾部"偷"任务执行，减少线程饥饿
- **RecursiveTask\<T\>**：有返回值的任务（类似 Callable）
- **RecursiveAction**：无返回值的任务（类似 Runnable）

### 使用示例

```java
// 并行求和（任务拆分策略由用户设计）
class SumTask extends RecursiveTask<Long> {
    private final int[] array;
    private final int lo, hi;
    static final int THRESHOLD = 1000;  // 任务粒度阈值

    SumTask(int[] array, int lo, int hi) { ... }

    @Override
    protected Long compute() {
        if (hi - lo <= THRESHOLD) {
            // 直接计算（足够小）
            long sum = 0;
            for (int i = lo; i < hi; i++) sum += array[i];
            return sum;
        }
        // 拆分为两个子任务
        int mid = (lo + hi) / 2;
        SumTask left  = new SumTask(array, lo,  mid);
        SumTask right = new SumTask(array, mid, hi);
        left.fork();              // 异步提交左侧任务
        return right.compute()    // 当前线程执行右侧
             + left.join();       // 等待左侧结果并合并
    }
}

ForkJoinPool pool = new ForkJoinPool();
long result = pool.invoke(new SumTask(array, 0, array.length));
```

**注意**：ForkJoin 仅适合 CPU 密集场景，若任务中有 IO 等待，工作窃取的优势消失，反而增加调度开销。

---

## 十一、关键概念速查

| 概念 | 一句话说明 |
|------|-----------|
| `synchronized` | 基于管程的内置锁，可重入，JVM 自动释放，支持锁升级优化 |
| `volatile` | 保证可见性 + 禁止重排序，不保证原子性 |
| `park/unpark` | LockSupport 的阻塞/唤醒，无需持有锁，可先 unpark 再 park |
| `CAS` | CPU 级原子比较并交换，乐观锁基础，存在 ABA 问题 |
| `LongAdder` | 分散累加单元减少 CAS 竞争，高并发下比 AtomicLong 快 |
| `AQS` | 同步器框架，提供 state + FIFO 队列 + park/unpark，ReentrantLock 等基于此实现 |
| `ReentrantLock` | AQS 实现的可重入锁，支持可中断、超时获取、公平/非公平、多 Condition |
| `ThreadPoolExecutor` | 线程池核心实现，7 个构造参数，任务超过核心线程数先入队再创救急线程 |
| `ForkJoinPool` | 工作窃取线程池，适合能递归拆分的 CPU 密集型任务 |
| `Future.get()` | 阻塞等待异步任务结果，出错时抛 ExecutionException |
| `FutureTask` | 同时实现 Runnable + Future，是 Callable 与 Thread 的桥梁 |
