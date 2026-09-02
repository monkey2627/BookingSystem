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
### 6. Future.get () 阻塞详解

```
Future<?> f1 = executor.submit(runnable);
```

- `submit()`：**提交任务给线程池，任务会在线程池内部的工作线程去跑**（新开 / 复用池子里线程）。
- 但是：`submit()` 这个方法本身**不会阻塞你写这行代码的线程**，提交完立刻返回 Future 对象，主线程继续往下执行。
- **`future.get()` 是谁调用，就阻塞谁，这个 “谁” 就是「当前线程」**。
- `Future.get()` 阻塞本质：**调用线程在内部等待队列上做 LockSupport.park ()；任务完成时，工作线程 unpark 唤醒等待线程**。

>
> FutureTask 内部没有操作系统层面的阻塞队列，靠 AQS (AbstractQueuedSynchronizer) 的状态机 + park/unpark 实现等待唤醒。
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
#### 不可中断
> 这句话针对：**`synchronized` 抢锁阻塞；还有 `ReentrantLock.lock()`，这两个等待锁的时候，不会响应中断**。
> ReentrantLock 想要等待锁可中断，必须用专属方法：**`lockInterruptibly()`**CSDN博...。

##### 什么叫中断 `thread.interrupt()`

`interrupt()` **不是直接杀死线程**，只是给线程打一个「中断标记」。

- 很多阻塞方法：`sleep()`、`Object.wait()`、`Future.get()`、`LockSupport.park()`，遇到中断标记会立刻苏醒，抛出 `InterruptedException`。
- **但是：在抢锁排队阻塞的时候（锁池），synchronized 完全无视这个中断标记**。

##### synchronized 场景（不可中断）

线程 A 拿到锁；线程 B 进入`synchronized`块抢锁，锁被占用，B 进入**锁池阻塞**。
此时别的线程调用 `B.interrupt()`：
✅ B 线程**不会苏醒，不会抛出异常，继续死死排队等锁**。

>
> 中断标记仅仅被设置；**要等到 B 真正抢到锁之后，代码往下执行，后面遇到 sleep/wait 才会感知到中断标记**。
### 锁升级（偏向锁、轻量级锁、重量级锁）与 Monitor
> 前提：这是 **`synchronized`** 的优化，只针对对象头里的 **MarkWord**。
> 重量级锁本质就是依赖操作系统 `Monitor`（管程）；偏向锁、轻量级锁是 JDK6 新增，**不调用操作系统Monitor，在用户态完成，避免内核态切换开销**。

每个 Java 对象都有 **对象头 Mark Word**，锁状态就存在 MarkWord 的标记位：


| 锁状态  | 标记位 |  是否使用Monitor |
|------|-----|------|
| **无锁** | 01  |否
| **偏向锁** | 01  |否
| **轻量级锁** | 00  |否
| **重量级锁** | 10  |使用ObjectMonitor(Monitor)

> 锁**只能升级，不能降级**：一旦膨胀成重量级锁，就回不去轻量/偏向。

### 什么是 Monitor（ObjectMonitor）
Monitor 是操作系统提供的管程，底层依赖操作系统互斥锁。
- 每个对象在需要的时候才会创建一个 `ObjectMonitor`（存在堆中）
- Monitor 内部：
    1. `_owner`：记录当前持有锁的线程
    2. `_EntryList`：**锁池**，抢锁失败阻塞的线程队列（操作系统阻塞线程）
    3. `_WaitSet`：调用 `wait()` 的等待线程队列

重量级锁：线程抢锁失败 → 进入 `EntryList`，**操作系统把线程挂起阻塞（内核态）**，CPU不再调度。
> 用户态 ↔ 内核态切换开销很大，JDK6不想一上来就用Monitor，于是搞出偏向锁、轻量级锁做优化。
#### _EntryList 与 _WaitSet 区别
> 只属于 **ObjectMonitor（重量级锁）**，偏向锁、轻量级锁没有这两个队列。
> 前提：线程已经进入重量级锁模式。

##### 快速一句话区分
- **_EntryList（锁池）：抢锁没抢到，在外面排队等锁。还没拿到锁。**
- **_WaitSet（等待池）：已经拿到锁了，主动调用 `wait()`，主动释放锁，去等待某个条件。**

##### 完整流程演示
`synchronized(obj) {`
1. 线程A抢到锁，`monitor._owner = A`。
2. 线程B、C同时来执行 `synchronized(obj)`，抢锁失败 → **进入 _EntryList**，操作系统阻塞。
> B、C就在 _EntryList：想要锁，拿不到，原地排队。

3. 线程A在同步块内部执行 `obj.wait()`：
    - A**释放锁**（`_owner=null`）
    - A线程从运行状态，移动到 **_WaitSet** 队列阻塞。
> ✅重点：调用wait()的前提：**当前线程必须已经持有monitor锁**。所以能进WaitSet的线程，都是曾经拿到过锁的。

4. 之后其他线程调用 `obj.notify() / notifyAll()`
    - notify：从 _WaitSet 拿出**一个线程**，把它移到 **_EntryList**，不是立刻唤醒拿到锁！
    - notifyAll：把 _WaitSet全部线程移动到 _EntryList。
    - 被挪出来的线程进入 _EntryList 和其他线程一起竞争锁。

> ❗非常高频坑：notify 不会直接把线程唤醒直接运行！只是挪到EntryList继续抢锁。

5. 当锁释放之后，monitor唤醒 _EntryList 中的线程去竞争锁。

##### 对比表格
|项目|_EntryList（锁池）|_WaitSet（等待池）|
|---|---|---|
|怎么进去|抢synchronized锁失败，拿不到锁|已经拿到锁，执行`wait()`主动释放锁进入|
|线程状态|想要锁，拿不到锁|已经放弃锁，等待条件唤醒|
|怎么出来|锁释放时，被monitor唤醒竞争锁|被notify/notifyAll移动到`_EntryList`，再参与锁竞争|
|是否持有锁|❌从未持有锁|✅进入之前持有锁；进入wait之后释放锁|
|触发操作|进入synchronized块抢锁失败|同步块内调用`obj.wait()`|

##### 时序小例子
```java
//线程A
synchronized(obj){
    obj.wait(); //A原本持有锁 →释放锁 →A进入 _WaitSet
}

//线程B
synchronized(obj){
    obj.notify(); //把A从WaitSet移到EntryList；A此时还拿不到锁！
}
//B退出synchronized，释放锁。此时才唤醒EntryList里A去竞争锁。
```

##### 高频面试坑
1. **wait() 必须写在synchronized内部**
   因为操作WaitSet队列是monitor内部数据，必须持有锁才能操作，否则抛 `IllegalMonitorStateException`。

2. notify之后线程不会立刻执行
   notify只是把线程从WaitSet搬到EntryList；依旧要排队抢锁。notify调用线程还占有锁，同步块没走完，锁不会释放。

3. 两个队列的线程**都是操作系统阻塞（重量级锁）**，不是自旋。

4. 虚假唤醒：WaitSet里面线程可能被意外唤醒，所以wait必须套在while循环判断条件，不能if。
```java
//标准写法
while(!condition){
    obj.wait();
}
```

##### 记忆口诀
> **抢不到锁去 EntryList；拿到锁主动wait去 WaitSet；notify把WaitSet的人丢回EntryList重新抢锁。**

###### 补充和锁升级关联
只有重量级锁才创建ObjectMonitor，才有这两个队列。
如果当前是偏向锁 / 轻量级锁，调用`wait()`，JVM会直接膨胀为重量级锁，创建Monitor对象，否则没有WaitSet，直接抛异常。
---

#### 1️⃣ 无锁 MarkWord(01)
对象没有被任何线程加锁，没有线程竞争。

#### 2️⃣ 偏向锁 MarkWord(01，偏向标识=1)
**场景：只有同一个线程反复拿锁，没有其他线程竞争。**
> 假想场景：for循环里面反复进入synchronized，始终就这一个线程。

- 第一次获取锁：做一次 CAS，把 MarkWord 里面的**线程ID记录到对象头**。
- **之后这个线程再次获取锁：完全不做CAS！直接拿锁。** 省去CAS开销。
> “偏向”：这个锁偏向这个线程，默认认定就是你用。

##### 偏向锁什么时候失效？
1. **出现第二个线程来抢锁** → 发生偏向撤销（revoke）。
   JVM需要**全局安全点STW**，暂停持有偏向锁的线程，撤销偏向锁，升级到轻量级锁。
2. 调用 `hashCode()`：偏向锁会被撤销。因为偏向锁的MarkWord存了线程ID，放不下hashcode。

> 偏向锁适合：**单线程反复获取锁，无竞争**；只要来了第二个线程，偏向锁就废掉。

#### 3️⃣ 轻量级锁 MarkWord(00)
**场景：多线程交替使用锁，不会同时争抢（竞争不激烈）。不使用Monitor！**

流程：
1. 线程在自己栈帧创建 **Lock Record（锁记录）**，保存对象旧的MarkWord（Displaced Mark Word）。
2. CAS尝试把对象MarkWord指向当前线程的Lock Record。
    - CAS成功：拿到轻量级锁。
3. 释放锁：CAS把Displaced MarkWord写回对象头。
## CAS 是什么
**CAS：Compare And Swap，比较并交换。CPU硬件指令级别原子操作**。
> 不是Java关键字，是CPU提供的原子指令；`Unsafe` 底层封装调用CPU的CAS指令。

### CAS逻辑（伪代码）
```java
// 三个参数：内存地址，预期旧值，想要设置的新值
boolean CAS(address, expectValue, newValue){
    if(内存[address] == expectValue){
        内存[address] = newValue;
        return true; // 修改成功
    }else{
        return false; // 已经被别人改了，修改失败
    }
}
```
**原子性：整个比较+赋值是CPU一条指令，不会被打断。不需要synchronized重量级锁就能保证原子。**

---

### 套回到【轻量级锁】的CAS场景
> 对象头 MarkWord 在堆内存；`Lock Record` 在当前线程的栈帧。

轻量级锁加锁时的CAS：
```
CAS(对象头MarkWord地址, 预期旧MarkWord值, 新值=当前线程栈中LockRecord的指针)
```
1. **expectValue：对象当前原始MarkWord（无锁状态下的markword）**
2. **newValue：当前线程栈帧里面 Lock Record 的内存地址指针**

#### CAS成功
对象头MarkWord改成指向栈里LockRecord，标记位变成`00` → 获取轻量级锁成功。
并且把**原来对象的旧MarkWord拷贝保存到LockRecord内部，这个旧值叫 Displaced Mark Word（位移MarkWord）**。

#### CAS失败
说明：**别的线程已经修改过这个对象MarkWord，已经抢占了轻量级锁**。
于是当前线程进入**自旋：循环反复执行这个CAS尝试抢锁**。
> 自旋 = while循环不停调用CAS，不进入操作系统阻塞，用户态空转消耗CPU。

> ⚠️重点：轻量级锁全程靠CAS，**不创建ObjectMonitor，不进入内核态，没有操作系统阻塞**。

### 轻量级锁释放时候同样CAS
释放锁：用CAS把保存在LockRecord里的 **Displaced MarkWord（旧MarkWord）写回对象头**。
```
CAS(对象头MarkWord地址, expect=LockRecord指针, newValue=Displaced MarkWord)
```
- CAS成功：锁释放完成，对象回到无锁状态01。
- CAS失败：代表已经发生竞争，锁已经膨胀成**重量级锁**，走重量级锁释放逻辑。

---

### 轻量级锁CAS失败两种走向
1. 自旋几次CAS成功：另一个线程已经释放锁，拿到轻量级锁，维持轻量级锁状态。适合**线程交替使用锁，没有真正同时竞争**。
2. 自旋多次CAS一直失败：说明两个线程**真正并发同时抢锁**。不再自旋，**锁膨胀，升级重量级锁，创建ObjectMonitor，线程进入操作系统阻塞**。

### CAS 三大经典问题（顺带记面试点）
1. **ABA问题**：值A→B→A；CAS看到还是A，以为没变化。需要版本号解决。
2. **循环自旋消耗CPU**：不断while‑CAS空转，轻量级锁自旋就是这个问题，竞争大就膨胀重量级锁避免空耗CPU。
3. **只能保证一个变量原子修改**，不能操作多个变量。

### 和 synchronized(Monitor)对比
- synchronized 重量级锁：靠操作系统管程Monitor，线程抢不到就OS阻塞（内核态切换，开销大）。
- CAS：CPU硬件指令，用户态，不需要内核切换；失败一般自旋重试；但是自旋会吃CPU。

### 如果CAS失败（有另一个线程尝试抢锁）：
线程做**自旋**：循环重试CAS，不阻塞线程（用户态空转，不进入操作系统）。

> ⚠️轻量级锁自旋：线程不放弃CPU，原地循环尝试抢锁。

#### 轻量级锁两种结局
1. 另一个线程很快释放锁：自旋CAS成功，拿到锁，依旧保持轻量级锁。
> ✅多线程交替，不同时抢，轻量级锁效率高。

2. **自旋多次拿不到锁（出现真正并发竞争：两个线程同时要锁）**
   → **锁膨胀！升级为重量级锁 MarkWord=10**。
   此时对象MarkWord指向堆里的 `ObjectMonitor`，**正式启用Monitor**。抢锁失败的线程进入Monitor的EntryList，操作系统把线程阻塞。

### 4️⃣ 重量级锁 MarkWord(10) —— Monitor登场
一旦升级重量级锁：
1. 对象MarkWord存的是 `ObjectMonitor` 的地址。
2. 抢到锁的线程：`monitor._owner = 当前线程`
3. 抢不到锁：线程进入 `EntryList`，**操作系统将线程阻塞（内核态）**，不再消耗CPU。
4. 锁释放后，Monitor唤醒EntryList里面等待的线程。

> `wait() / notify()` 只能在重量级锁即synchronizad内部工作！
> 1. 如果对象当前是**偏向锁 或者 轻量级锁状态**，在同步块内调用 `obj.wait() / notify()`：
     ✅ **不会抛异常！JVM 会主动把锁膨胀升级为重量级锁，创建 ObjectMonitor，然后执行 wait 逻辑**。
> 2. 如果**根本没有进入 synchronized 同步块（完全没拿到锁）**，直接调用 `obj.wait()`：
   ❌ 直接抛 `IllegalMonitorStateException`，这个是 Java 语法层面检查，和锁升级无关

---

### 完整锁升级流转
```
无锁(01)
    ↓ 同一个线程第一次获取synchronized
偏向锁(01 偏向标记1)
    ↓ 来了第二个线程竞争 → STW撤销偏向
轻量级锁(00) 【CAS自旋，用户态，无Monitor】
    ↓ 自旋失败，真正并发竞争
重量级锁(10)【启用ObjectMonitor，OS阻塞线程】
```
> 不可逆：重量级锁**不会降级**，就算竞争消失也回不到轻量/偏向。

### 三个锁适用场景对比（面试必背）
|锁|适用场景|实现手段|是否Monitor|代价|
|---|---|---|---|---|
|偏向锁|**单线程反复获取锁，无竞争**|对象头记录线程ID，后续不加CAS|❌|极低|
|轻量级锁|**多线程交替访问，几乎不并发竞争**|栈中Lock Record + CAS自旋|❌|CAS自旋消耗CPU|
|重量级锁|**线程并发同时抢锁**|ObjectMonitor，操作系统阻塞线程|✅|用户内核切换，阻塞开销大|

## 高频易错坑
1. ❌误区：偏向锁、轻量级锁是给Lock用的。
   ✅只针对 `synchronized`；`ReentrantLock` 是AQS实现，和这套MarkWord锁升级完全无关。

2. ❌误区：轻量级锁自旋就是一直无限自旋。
   JDK6之后自适应自旋：JVM根据历史统计，自动调整自旋次数；自旋拿不到立刻膨胀重量级。

3. ❌误区：锁可以降级。
   ✅一旦膨胀重量级锁，**不会降级**。

4. `wait()` 为什么不能在偏向/轻量级锁调用？
> wait需要把线程放进Monitor的WaitSet；偏向、轻量级锁没有创建ObjectMonitor对象，直接抛 IllegalMonitorStateException。只有重量级锁才有Monitor。

5. hashCode() 会破坏偏向锁：偏向锁MarkWord存线程ID，没有位置存hashcode，一旦调用hashCode，直接撤销偏向锁。

#### MarkWord极简示意图（简化版）
```
偏向锁 markWord: [线程ID][epoch][0][1]
轻量级锁 markWord: [指向栈LockRecord指针][00]
重量级锁 markWord: [指向ObjectMonitor地址][10]
```

#### 一句话串讲
> 为了避免一上来就调用操作系统Monitor（重量级锁，开销大），synchronized做分层优化：
> 只有单线程就用偏向锁；多线程交替抢锁用轻量级锁CAS自旋；
> 一旦真正发生并发竞争，就膨胀重量级锁，创建ObjectMonitor，线程交给操作系统阻塞。锁只能升级，不能降级。


## 三、LockSupport.park() / LockSupport.unpark() 


`LockSupport` 是 JDK 提供的 `线程阻塞` 工具类，相当于你手里有两个指令：`睡觉()`、`叫醒(线程)`。

```java
// 暂停当前线程（消耗一个"许可"，若无许可则阻塞）
LockSupport.park();

// 恢复指定线程（发放一个"许可"）
LockSupport.unpark(thread);
```

> 1.可以先unpark再park
> 
> 2.你可以让任意人睡觉、叫醒任意人。但是**没有任何 “锁” 概念**，
> 你要自己写代码控制谁能进临界区。AQS 就是干这件事：维护 state、等待队列，
> 竞争失败调用 park 睡觉。底层封装操作系统条件变量，JVM 额外增加 permit 许可变量；**只做线程休眠唤醒，本身不实现锁与互斥**。AQS 在它之上实现锁。

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
### 工作内存 & 主内存（JMM Java内存模型）
> ⚠️ **JMM 是抽象模型，不是JVM真实物理内存布局！不要和硬件1:1硬绑定。**

#### 1.JMM定义
- **主内存：所有线程共享**
  存储所有对象实例、成员变量，所有线程都可以访问。对应硬件：物理内存RAM。

- **工作内存：每个线程私有，线程独有**
  每个线程拥有自己独立的工作内存。
> 抽象对应硬件：CPU寄存器、L1/L2高速缓存。
> **工作内存是抽象概念，JVM里没有一块叫“工作内存”的真实内存区域。**

线程不能直接读写主内存变量；线程操作变量流程（JMM抽象规则）：
1. 线程先把共享变量副本，从**主内存拷贝到自己的工作内存**
2. 线程读写修改，都是操作**自己工作内存里的副本**
3. 在某个时机，再把副本写回主内存。

> 这就是可见性问题根源：线程A修改自己工作内存副本，还没刷回主内存；线程B工作内存还是旧副本，看不到修改。

---

#### 但是：抽象 vs 真实硬件，这里有一个很容易踩的坑
> “工作内存是线程独有”是**JMM模型层面的描述**。
落到真实CPU硬件上：
1. CPU缓存是**CPU核心私有**，**不是线程私有**！
   线程是操作系统调度单位；CPU核心是硬件。
- 同一个CPU核心，可以轮流调度多个不同线程。
- 一个线程，也可能操作系统调度，一会跑在CPU‑0，一会切到CPU‑1。

👉 也就是说：
- JMM：**工作内存 = 线程私有（模型）**
- 硬件：**CPU缓存 = CPU核心私有，不是线程私有（物理事实）**

JMM做了一层抽象屏蔽掉硬件细节：
不管线程被OS调度到哪个CPU核心运行，**逻辑上认为这个线程拥有一份私有的工作内存副本**。
这是模型上的等价描述，方便程序员理解并发可见性，不是硬件真实划分。

##### 举个例子
线程T1，一开始跑在CPU‑0，变量副本存在CPU‑0的L1缓存。
操作系统发生线程切换，T1被调度到CPU‑1上运行。
此时T1会在CPU‑1缓存加载一份新副本。
在JMM模型看来：始终是T1在操作它自己那一份“工作内存”。

#### volatile 在这套模型下怎么理解（JMM角度）
1. **volatile写：**
   线程修改工作内存副本之后，**立刻刷新回主内存**，并且让其他线程工作内存中的该变量副本失效。
2. **volatile读：**
   线程**废弃自己工作内存中的副本，直接从主内存读取最新的值**。

> 注意：上面这一套是**JMM抽象层面的教科书说法**。
> 底层硬件不是真的每次都去读RAM主存，而是MESI缓存一致性协议+内存屏障实现；考试、面试答题写JMM这套抽象描述完全没问题。

#### synchronized JMM语义
1. 进入同步块：把共享变量从主内存加载到线程工作内存。
2. 退出同步块：把工作内存修改全部刷回主内存。
   所以synchronized也自带可见性。

#### 总结表格
|概念|归属|JMM抽象含义|硬件对应|
|---|---|---|---|
|主内存|所有线程共享|存放共享成员变量|物理内存RAM|
|工作内存|**线程独有（JMM模型）**|线程私有变量副本，线程操作变量都在这里|CPU寄存器、CPU缓存（CPU核心私有，并非线程硬件隔离）|

##### 面试标准回答
> 在Java内存模型JMM中，**主内存是所有线程共享的，保存共享对象的成员变量；工作内存是每个线程私有的抽象存储空间，每个线程会拷贝共享变量的副本到自己工作内存，线程只能操作副本。工作内存对应硬件上CPU寄存器、CPU高速缓存；注意硬件上缓存是CPU核心私有，并非线程私有，JMM做了抽象屏蔽硬件调度差异。普通变量修改只发生在当前线程工作内存，不一定及时同步主存，由此产生可见性问题。volatile、synchronized可以解决可见性。**

> 重点记忆两句话：
> 1. JMM模型：工作内存**线程独有**，主内存**线程共享**。（答题写这个）
> 2. 真实硬件：缓存是CPU核心私有，不是线程私有；线程会被OS在不同CPU核心之间切换。（自己理解，区分模型和硬件）
---

### volatile保证有序性
> 指令重排序：CPU / 编译器为了性能，在不改变单线程语义前提下，调换指令执行顺序。
内存屏障的本质：**限制屏障两侧的指令，不允许跨越屏障做重排序**。

四个基础内存屏障：
1. `LoadLoad`
2. `LoadStore`
3. `StoreStore`
4. `StoreLoad`

> Load = 读（从内存加载数据到寄存器）
> Store = 写（把寄存器数据写回内存/缓存）

---

#### StoreStore 屏障
```
Store A      // 写A
|| StoreStore 屏障
Store B      // 写B
```
规则：
✅ A、B都是写。**不允许把 Store‑B 重排到 Store‑A 的前面**。
> 屏障之前的写，必须全部完成，才执行屏障之后的写。
> 屏障左侧内部可以重排；屏障右侧内部可以重排；不能跨屏障。

#### LoadLoad 屏障
```
Load A
|| LoadLoad
Load B
```
禁止：`Load‑B` 跑到 `Load‑A` 的前面。读A必须先完成，再读B。

#### LoadStore 屏障
```
Load A
|| LoadStore
Store B
```
禁止：`Store‑B` 跑到 `Load‑A` 的前面。读完成之后，才能执行写。

#### StoreLoad
```
Store A
|| StoreLoad
Load B
```
禁止两件事：
1. 不允许后面的 `Load‑B` 跑到前面 `Store‑A` 之前
2. 不允许前面的 `Store‑A` 跑到后面 `Load‑B` 之后

> StoreLoad 是开销最大的屏障；

---

#### volatile 的内存屏障插入规则
1. **volatile 写操作：**
> 在 volatile‑write 的**前面插入 StoreStore**，**后面插入 StoreLoad**
```
普通读写...
|| StoreStore
volatile x = v;   // volatile写
|| StoreLoad
普通读写...
```
- StoreStore：保证前面所有普通写，全部完成，才执行volatile写。
- StoreLoad：保证 volatile写完成之后，后面所有读才可以执行。防止后面读跑到volatile写前面。

2. **volatile 读操作：**
> 在 volatile‑read 的**后面插入 LoadLoad + LoadStore**
```
普通读写...
int v = volatileX; // volatile读
|| LoadLoad
|| LoadStore
普通读写...
```
- LoadLoad：volatile读完成，后面所有读不能跑到它前面。
- LoadStore：volatile读完成，后面所有写不能跑到它前面。

> volatile 读**前面不插屏障**。
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

# await() 不是抢锁！完全相反
> `lock.await()` ≠ lock.lock()
> `await()`：**我已经拿到锁了，现在我主动把锁释放掉，我去睡觉等待别人signal唤醒我**。

对应 synchronized 的 `obj.wait()`。

## 完整时序，一步看懂
```java
lock.lock();   // 1. ✅这里才是抢锁！现在线程已经持有锁
try {
    while (!condition) {
        cond.await(); // 2. ⚠️不是抢锁！
        /*
        await内部做三件大事：
        ① 把当前线程封装Node，加入Condition的条件等待队列
        ② **释放ReentrantLock锁！state会减少到0，别的线程可以拿到锁**
        ③ LockSupport.park()，当前线程阻塞睡觉
        */
    }
    //被signal唤醒之后，代码走到这里
    //❗await返回之前，会**重新竞争获取锁**！拿到锁之后，await函数才return
} finally {
    lock.unlock();
}
```

### 关键流程拆解
1. 调用 `await()` 的**前提：必须已经持有锁**，否则抛异常。
2. 调用 await：**主动释放锁，线程休眠**。此时别的线程可以获取这把锁。
3. 别的线程调用 `cond.signal()`：把这个线程节点从 Condition条件队列移到 **AQS锁竞争队列**。
   > signal只是挪位置，**不会立刻唤醒运行**！
4. signal之后，等到别的线程执行 `unlock()`释放锁。被唤醒的线程才会在AQS队列里**重新抢锁**。
5. **只有抢锁成功之后，`await()` 函数才返回，继续往下执行代码**。

👉 所以：**抢锁这个动作，发生在 await 返回的时候，不是调用 await 的时候。**

## 通俗比喻
- `lock()`：排队抢房间钥匙，拿到钥匙进入房间（临界区）。
- `await()`：我拿着钥匙在房间里，条件不满足，**把钥匙交出去，我到门外休息室睡觉（Condition队列）**。别人可以拿钥匙进房间。
- `signal()`：有人喊我：“条件好了！出来排队拿钥匙！” 把我从休息室赶到抢钥匙的队伍(AQS队列)。
- 我还要排队重新抢钥匙；拿到钥匙之后，await才算结束，回到房间继续干活。
- `unlock()`：我干完活，主动交出钥匙。

## 极易混淆对比
|方法|作用|是否持有锁|
|---|---|---|
|`lock()` / `lockInterruptibly()`|**抢锁，获取锁**|调用结束后持有锁|
|`cond.await()`|**释放锁，阻塞等待信号**|调用时持有锁；调用中释放锁；函数返回前重新抢到锁|
|`cond.signal()`|唤醒一个等待线程，迁移节点到AQS队列|调用时必须持有锁|
|`unlock()`|释放锁|调用时必须持有锁|

## 面试高频坑
1. await 返回之后，**一定是已经持有锁的状态**，所以后面代码可以直接操作共享变量，最后finally可以直接unlock。
2. await 必须套 while 循环，防止虚假唤醒：线程被唤醒，但条件依然不满足，此时已经拿到锁，继续await释放锁休眠。
```java
// 正确
while (!canDo) {
    cond.await();
}
//错误 if(!canDo)  虚假唤醒之后直接往下跑，条件不满足也执行业务
```
3. signal 不释放锁！signal只是移动节点；调用signal的线程还拿着锁，直到退出try‑finally执行unlock锁才释放。

### 一句话记牢
> **lock() 是抢锁；await() 是已经拿到锁，主动放弃锁去睡觉；被唤醒后 await 内部会自动重新抢锁，抢成功函数才返回。**
>
# 锁 和 Condition条件：是两个独立东西，但强绑定
✅**是的，锁(Lock) 和 条件(Condition) 是两个不同对象，两件不同事物。**

拿 `synchronized(obj)` + `obj.wait()` 先对照理解：
- **锁：就是对象Monitor管程，负责互斥，保证同一时间只有一个线程进临界区。**
- **条件：WaitSet等待队列，负责“条件不满足的时候线程睡觉”。**

在synchronized里：锁和条件**强制绑定死在一起**。`obj` 的Monitor自带唯一的`_WaitSet`条件等待池。
> 你只能用这个obj锁对应的wait/notify；一把锁只能有一个等待池。

```java
synchronized(obj){
    obj.wait(); // wait属于obj这个Monitor自带唯一条件队列，不能新建别的
}
```

---

## ReentrantLock 中 Lock 与 Condition
```java
ReentrantLock lock = new ReentrantLock(); //锁对象，负责互斥，AQS state、AQS CLH锁队列
Condition notFull = lock.newCondition();  //条件1：仓库不满
Condition notEmpty = lock.newCondition(); //条件2：仓库不为空
```
1. **lock（锁）**
   负责互斥：`lock()` / `unlock()`，AQS的state、AQS双向CLH**锁队列**。
   线程抢锁失败，进入AQS锁队列排队。

2. **Condition（条件）**
   每个Condition实例内部维护自己**单向条件等待队列**。
   负责：`await()`睡觉、`signal()`唤醒。

> ⚠️重要规则：Condition不能脱离锁独立使用！
> `lock.newCondition()` 创建出来的条件，**牢牢绑定创建它的那把锁**。
- 调用 `await() / signal() / signalAll()`，线程**必须持有这个绑定的ReentrantLock锁**，否则抛异常。
- 不能拿A锁创建的Condition，在B锁保护的代码里调用。

### 两套队列，千万不要搞混
1. **AQS锁队列（CLH双向链表）：锁的队列**
   抢锁拿不到锁的线程在这里排队。线程想要进入临界区就要竞争这个队列。

2. **Condition条件队列（单向链表）：条件的队列**
   线程**已经拿到锁**，但是业务条件不满足，主动`await()`释放锁，进入条件队列睡觉。

#### signal做的核心动作
> signal **不会直接把线程丢去运行**：把Node节点，从【Condition条件队列】移除，移动到【AQS锁队列】。
线程到AQS锁队列之后，继续排队竞争锁。

> 所以：await 完整生命周期：
>拿到锁 → await释放锁 →进入Condition条件队列睡觉 →signal迁移到AQS锁队列 →在AQS队列重新抢锁 →抢到锁，await返回。

## 生产者消费者例子：一把锁，两个条件
```java
ReentrantLock lock = new ReentrantLock();
Condition notFull = lock.newCondition();
Condition notEmpty = lock.newCondition();

//生产者
lock.lock();
try{
    while(队列满){
        notFull.await(); //仓库满，在notFull条件队列睡觉，释放锁
    }
    putData();
    notEmpty.signal(); //通知消费者：有数据了
}finally {
    lock.unlock();
}

//消费者
lock.lock();
try{
    while(队列空){
        notEmpty.await(); //仓库空，在notEmpty条件队列睡觉，释放锁
    }
    takeData();
    notFull.signal(); //通知生产者：有空位
}finally {
    lock.unlock();
}
```
- 同一把锁保证操作队列互斥。
- 两个条件分开两个等待队列：生产者睡在`notFull`；消费者睡在`notEmpty`。
- signal可以精准唤醒对应类型线程，避免`notifyAll()`全部唤醒带来大量无效竞争（虚假唤醒）。

> synchronized只有一个WaitSet；生产者消费者全部挤同一个等待池，notifyAll全部唤醒，很多线程醒来发现条件依旧不满足，又继续wait。

## 对比总结表
|项目|锁 Lock(ReentrantLock)|条件 Condition|
|---|---|---|
|职责|互斥保护临界区；控制谁能进入|条件不满足时让线程休眠、唤醒线程|
|内部队列|AQS‑CLH锁队列（双向）|Condition条件队列（单向）|
|获取方式|new ReentrantLock()|`lock.newCondition()`，**由锁生成，绑定锁**|
|关键API|lock()、unlock()|await()、signal()、signalAll()|
|进入队列时机|抢锁失败，拿不到锁|**已经拿到锁，条件不满足主动await释放锁**|
|离开队列|unlock之后被唤醒竞争锁|signal把节点迁移到AQS锁队列，再竞争锁|

## 两句话记忆
1. **锁管互斥，条件管等待。二者是不同对象，但Condition必须绑定一把锁。**
2. synchronized把锁和唯一条件池捆绑在一起；ReentrantLock允许一把锁生成多个独立Condition条件。

> 常见误区：
> ❌Condition本身不是锁！await**不会抢锁**；await是释放锁睡觉；被signal迁移到AQS锁队列后，才重新去抢锁。
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
