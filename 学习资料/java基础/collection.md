# Java 集合框架完全详解

---

## 一、框架总览

### 接口层次图

```
Iterable
└── Collection
    ├── List（有序，允许重复）
    │   ├── ArrayList
    │   ├── LinkedList
    │   ├── Vector（遗留，线程安全）
    │   └── Stack（遗留，继承 Vector）
    ├── Set（不允许重复）
    │   ├── HashSet（无序）
    │   │   └── LinkedHashSet（维护插入顺序）
    │   ├── TreeSet（自然序/自定义序）
    │   └── EnumSet（枚举专用，位向量实现）
    └── Queue（队列，FIFO）
        ├── PriorityQueue（堆，非 FIFO）
        ├── LinkedList（同时实现 Deque）
        └── Deque（双端队列）
            ├── ArrayDeque（首选）
            └── LinkedList

Map（键值对映射，不继承 Collection）
├── HashMap（无序，允许 null 键/值）
│   └── LinkedHashMap（维护插入或访问顺序）
├── TreeMap（按键排序）
├── Hashtable（遗留，线程安全，不允许 null）
├── WeakHashMap（弱引用键，GC 可回收）
├── IdentityHashMap（用 == 而非 equals 比较键）
└── EnumMap（枚举键专用，数组实现）

并发集合（java.util.concurrent）
├── ConcurrentHashMap
├── CopyOnWriteArrayList / CopyOnWriteArraySet
├── ConcurrentLinkedQueue（无锁队列）
├── ConcurrentSkipListMap / ConcurrentSkipListSet（跳表）
└── BlockingQueue 体系
    ├── ArrayBlockingQueue（有界，数组）
    ├── LinkedBlockingQueue（可有界，链表）
    ├── PriorityBlockingQueue（优先级，无界）
    ├── DelayQueue（延迟元素）
    └── SynchronousQueue（容量为0，直接交接）
```

---

## 二、核心接口详解

### Iterable

所有集合的顶层接口，定义了 `iterator()` 方法，支持 for-each 语法糖。

```java
public interface Iterable<T> {
    Iterator<T> iterator();
    default void forEach(Consumer<? super T> action) { ... }
    default Spliterator<T> spliterator() { ... }
}
```

`Iterator` 的 `remove()` 是在迭代过程中安全删除元素的唯一方式，直接调用集合的 `remove()` 会触发 `ConcurrentModificationException`。

### Collection

```java
public interface Collection<E> extends Iterable<E> {
    int size();
    boolean isEmpty();
    boolean contains(Object o);
    boolean add(E e);
    boolean remove(Object o);
    boolean containsAll(Collection<?> c);
    boolean addAll(Collection<? extends E> c);
    boolean removeAll(Collection<?> c);
    boolean retainAll(Collection<?> c);   // 取交集，保留都有的
    void clear();
    Object[] toArray();
    default Stream<E> stream() { ... }
    default Stream<E> parallelStream() { ... }
}
```

**`contains` 的范围与性能**：`contains(Object o)` 定义在 Collection 接口，所有 List、Set、Queue/Deque 实现类都有。**Map 不继承 Collection，没有 `contains`，替代方法是 `containsKey` 和 `containsValue`**。各实现类的复杂度差异很大：

| 集合 | contains 复杂度 | 原因 |
|------|---------------|------|
| ArrayList / LinkedList | O(n) | 线性扫描 |
| HashSet | O(1) | 哈希查找 |
| TreeSet | O(log n) | 红黑树 |
| ArrayDeque / PriorityQueue | O(n) | 线性扫描 |
| HashMap.containsKey | O(1) | 哈希查找 |
| HashMap.containsValue | O(n) | 全量扫描 value |

**实践原则：频繁判断元素是否存在，用 `HashSet`，不要用 `List`。**

### List

有序（按插入顺序），允许重复，允许 null。额外定义了按索引操作的方法：

```java
E get(int index);
E set(int index, E element);
void add(int index, E element);
E remove(int index);
int indexOf(Object o);          // 第一次出现的位置，没有返回 -1
int lastIndexOf(Object o);      // 最后一次出现的位置
List<E> subList(int from, int to);  // 视图，修改影响原 List
ListIterator<E> listIterator();     // 支持双向遍历和修改
```

### Set

不允许重复（通过 `equals()` 判断），最多一个 null（HashSet 允许，TreeSet 不允许）。
接口方法与 Collection 相同，语义约束更严格。

### Queue

FIFO（PriorityQueue 例外），定义了两套操作：

| 操作 | 失败抛异常 | 失败返回特殊值 |
|------|----------|-------------|
| 入队 | `add(e)` | `offer(e)` → false |
| 出队 | `remove()` | `poll()` → null |
| 查看队头 | `element()` | `peek()` → null |

**实际使用始终用 offer/poll/peek**，避免异常处理开销。

### Deque（双端队列）

继承 Queue，两端都可以入队/出队，可以当 Stack 用：

```java
// 队头操作
void addFirst(E e) / boolean offerFirst(E e)
E removeFirst() / E pollFirst()
E getFirst() / E peekFirst()

// 队尾操作
void addLast(E e) / boolean offerLast(E e)
E removeLast() / E pollLast()
E getLast() / E peekLast()

// 当 Stack 用（LIFO）
void push(E e)   // = addFirst
E pop()          // = removeFirst
E peek()         // = peekFirst
```

**不要用 `Stack` 类**（线程安全但性能差），用 `Deque<Integer> stack = new ArrayDeque<>()` 代替。

### Map

不继承 Collection，独立体系：

```java
V get(Object key);
V put(K key, V value);
V remove(Object key);
boolean containsKey(Object key);
boolean containsValue(Object value);
Set<K> keySet();
Collection<V> values();
Set<Map.Entry<K,V>> entrySet();   // 遍历首选

// Java 8 新增
V getOrDefault(Object key, V defaultValue);
V putIfAbsent(K key, V value);
V computeIfAbsent(K key, Function<K,V> mappingFunction);
V computeIfPresent(K key, BiFunction<K,V,V> remappingFunction);
V merge(K key, V value, BiFunction<V,V,V> remappingFunction);
void forEach(BiConsumer<K,V> action);
```

### SortedSet / NavigableSet

`SortedSet` 在 Set 基础上保证元素有序，额外方法：
```java
E first() / E last()
SortedSet<E> headSet(E toElement)    // < toElement
SortedSet<E> tailSet(E fromElement)  // >= fromElement
SortedSet<E> subSet(E from, E to)    // [from, to)
```

`NavigableSet` 扩展 SortedSet：
```java
E floor(E e)    // <= e 的最大元素
E ceiling(E e)  // >= e 的最小元素
E lower(E e)    // < e 的最大元素
E higher(E e)   // > e 的最小元素
E pollFirst() / E pollLast()
NavigableSet<E> descendingSet()  // 逆序视图
```

---

## 三、List 实现类

### ArrayList

**底层结构**：动态数组（`Object[] elementData`）

**关键字段**：
```java
transient Object[] elementData;  // 实际存储数组，transient 自定义序列化（只序列化有效元素）
private int size;                 // 当前元素数量（不是数组长度）
private static final int DEFAULT_CAPACITY = 10;
```

**初始化**：
```java
new ArrayList()      // elementData = DEFAULTCAPACITY_EMPTY_ELEMENTDATA（懒初始化，首次 add 才分配 10）
new ArrayList(0)     // elementData = EMPTY_ELEMENTDATA（立即分配 0 容量数组）
new ArrayList(n)     // elementData = new Object[n]
```

**扩容机制**（`grow()`）：

```java
// 触发时机：add 时 size == elementData.length
private Object[] grow(int minCapacity) {
    int oldCapacity = elementData.length;
    if (oldCapacity > 0 || elementData != DEFAULTCAPACITY_EMPTY_ELEMENTDATA) {
        int newCapacity = ArraysSupport.newLength(oldCapacity,
            minCapacity - oldCapacity,   // 最小需要增加的量
            oldCapacity >> 1);           // 期望增加的量 = 原容量的 0.5 倍
        // 实际新容量 = max(minCapacity, oldCapacity * 1.5)
        return elementData = Arrays.copyOf(elementData, newCapacity);
    } else {
        return elementData = new Object[Math.max(DEFAULT_CAPACITY, minCapacity)];
    }
}
```

扩容因子 **1.5 倍**（`oldCapacity + oldCapacity >> 1`），底层 `Arrays.copyOf()` 调用 `System.arraycopy()`（native 方法）。

**时间复杂度**：
- `get(index)`：O(1) 直接数组寻址
- `add(e)`（尾部）：O(1) 均摊（偶发扩容 O(n)）
- `add(index, e)`（中间）：O(n) 需要移位 `System.arraycopy()`
- `remove(index)`：O(n) 需要移位
- `contains(o)`：O(n) 线性扫描

**fail-fast 机制（modCount）**：

```java
protected transient int modCount = 0;  // 结构性修改次数（add/remove/clear）
```

每次结构性修改 `modCount++`。Iterator 创建时记录 `expectedModCount = modCount`，每次 `next()` 检查是否相等，不等就抛 `ConcurrentModificationException`。这只是快速失败，不保证并发安全。

**序列化优化**：`elementData` 是 `transient` 的，通过自定义 `writeObject()` 只序列化 `size` 个有效元素，避免序列化数组尾部的空槽。

**线程安全**：不是线程安全的。多线程用 `CopyOnWriteArrayList` 或 `Collections.synchronizedList()`。

**常见 API 陷阱**：

```java
// 陷阱1：get(index) 的参数是索引，不是元素值
// 错误：想判断元素 key 是否存在
if (list.get(key) != null) { ... }   // 实际是取下标 key 处的元素，越界或语义错误
// 正确：
if (list.contains(key)) { ... }      // 按值查找

// 陷阱2：remove(int index) 与 remove(Object o) 的重载歧义
// 当元素类型是 Integer 时，直接传 int 字面量会匹配 remove(int index) 而不是 remove(Object o)
List<Integer> list = new ArrayList<>(List.of(1, 2, 3));
list.remove(1);           // 删的是下标1的元素"2"，而不是值为1的元素！
list.remove((Integer) 1); // 正确：强转为 Integer，匹配 remove(Object o)，删值为1的元素
```

---

### LinkedList

**底层结构**：双向链表，同时实现 `List` 和 `Deque`

```java
private static class Node<E> {
    E item;
    Node<E> next;
    Node<E> prev;
}

transient Node<E> first;   // 头节点
transient Node<E> last;    // 尾节点
transient int size = 0;
```

**时间复杂度**：
- `get(index)`：O(n)，需要从头/尾二分方向遍历（index < size/2 从头，否则从尾）
- `add(e)`（尾部）：O(1) 修改 last 指针
- `addFirst(e)` / `addLast(e)`：O(1)
- `add(index, e)`（中间）：O(n) 先找节点，再 O(1) 插入
- `remove()`（头/尾）：O(1)
- `contains(o)`：O(n)

**与 ArrayList 的选择**：

几乎在所有场景 **ArrayList 都优于 LinkedList**：
- LinkedList 每个节点有 prev/next 指针额外内存开销，内存不连续导致 CPU 缓存命中率低
- LinkedList 的 O(1) 中间插入实际上需要先 O(n) 找到位置，并不比 ArrayList 快
- 只有在**只需要在头尾频繁操作**且**不需要随机访问**的场景，LinkedList 才有优势（但此时 ArrayDeque 更好）

**常见陷阱：声明类型决定可见方法**：

LinkedList 同时实现了 `List` 和 `Deque`，但声明为 `List<>` 时，编译器只认识 List 接口方法，Deque 专有方法（`getFirst()`、`getLast()`、`addFirst()`、`peekLast()` 等）无法调用：

```java
// 错误：声明为 List 接口，Deque 方法不可见
List<Integer> q = new LinkedList<>();
q.getFirst();   // 编译错误：List 接口没有 getFirst()

// 正确：声明为 LinkedList 或 Deque，Deque 方法才可见
LinkedList<Integer> q = new LinkedList<>();
q.getFirst();   // OK

Deque<Integer> q = new LinkedList<>();
q.peekFirst();  // OK（Deque 接口定义了此方法）
```

规则：**变量类型决定编译期可见的方法集**，用哪个接口声明就只能调哪个接口定义的方法。需要 Deque 功能就必须用 `Deque<>` 或 `LinkedList<>` 声明。

---

### Vector（遗留，了解即可）

与 ArrayList 几乎完全相同，区别：
- 所有公共方法都用 `synchronized` 修饰（方法级锁）
- 扩容因子为 **2 倍**（`capacityIncrement` 为 0 时），可以指定
- Java 1.0 就有，历史遗留，**不推荐使用**

### Stack（遗留，了解即可）

继承 Vector，在其基础上加了 push/pop/peek 方法。因为继承 Vector，所有 Vector 的方法都能调用，破坏了 Stack 的封装。**用 `Deque<T> stack = new ArrayDeque<>()` 代替。**

---

## 四、Set 实现类

### HashSet

**底层**：持有一个 `HashMap<E, Object>`，元素作为 Map 的 key，所有 key 共享同一个 dummy value `PRESENT = new Object()`。

```java
private transient HashMap<E,Object> map;
private static final Object PRESENT = new Object();

public boolean add(E e) {
    return map.put(e, PRESENT) == null;   // put 返回 null 说明是新 key
}
public boolean contains(Object o) {
    return map.containsKey(o);
}
```

所有特性（无序、O(1) 增删查、允许 null、fail-fast）都来自 HashMap。

---

### LinkedHashSet

**底层**：继承 HashSet，构造时传入参数让父类使用 `LinkedHashMap` 而不是 `HashMap`。

```java
// HashSet 中的特殊构造器（包级别访问）
HashSet(int initialCapacity, float loadFactor, boolean dummy) {
    map = new LinkedHashMap<>(initialCapacity, loadFactor);
}
```

**保持插入顺序**，遍历顺序与 add 顺序相同。性能略低于 HashSet（维护双向链表）。

---

### TreeSet

**底层**：持有一个 `TreeMap<E, Object>`，元素作为 key。

```java
private transient NavigableMap<E,Object> m;
```

**特点**：
- 元素必须实现 `Comparable`，或构造时传入 `Comparator`
- 元素不允许 null（比较时会 NullPointerException）
- 有序（自然序或自定义序），实现 `NavigableSet`
- 所有操作 O(log n)（红黑树）

**常用场景**：需要保持排序的唯一元素集合；range 查询（`headSet`/`tailSet`/`subSet`）。

---

### EnumSet

**底层**：位向量（long 数组）。每个枚举值对应一个 bit。

- `RegularEnumSet`：枚举常量 ≤ 64 个，用单个 `long` 存储
- `JumboEnumSet`：枚举常量 > 64 个，用 `long[]` 存储

**特点**：性能极高（位运算），比 HashSet 快一个数量级，内存极省，但只能存同一枚举类型的值。

```java
EnumSet<DayOfWeek> weekend = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
EnumSet<DayOfWeek> workdays = EnumSet.complementOf(weekend);  // 取补集
EnumSet<DayOfWeek> all = EnumSet.allOf(DayOfWeek.class);
```

---

## 五、Queue 与 Deque 实现类

### PriorityQueue（优先队列）

**底层**：二叉最小堆，用数组实现。

```java
transient Object[] queue;   // 存储堆元素
private int size = 0;
private final Comparator<? super E> comparator;  // null 表示自然序
```

**堆的数组表示**：
```
下标 i 的节点：
  父节点：(i - 1) >>> 1     即 (i-1)/2
  左子节点：2 * i + 1
  右子节点：2 * i + 2

数组：[1, 3, 2, 7, 4, 5, 6]
对应的堆：
         1
       /   \
      3     2
     / \   / \
    7   4 5   6
```

**核心操作**：

`offer(e)`（入队，O(log n)）：
```java
// 1. 添加到数组末尾
// 2. siftUp（上浮）：与父节点比较，小于父节点则交换，直到满足堆性质
private static <T> void siftUpComparable(int k, T x, Object[] es) {
    while (k > 0) {
        int parent = (k - 1) >>> 1;
        Object e = es[parent];
        if (((Comparable<? super T>)x).compareTo((T) e) >= 0)
            break;           // x >= parent，堆性质满足，停止
        es[k] = e;           // 父节点下移
        k = parent;
    }
    es[k] = x;
}
```

`poll()`（出队堆顶，O(log n)）：
```java
// 1. 取出堆顶元素（数组[0]）
// 2. 把数组最后一个元素移到堆顶
// 3. siftDown（下沉）：与较小的子节点比较，大于子节点则交换，直到满足堆性质
private static <T> void siftDownComparable(int k, T x, Object[] es, int n) {
    int half = n >>> 1;   // 只需比较到第一个叶子节点之前
    while (k < half) {
        int child = (k << 1) + 1;   // 左子节点
        Object c = es[child];
        int right = child + 1;
        if (right < n && ((Comparable<? super T>)c).compareTo((T)es[right]) > 0)
            c = es[child = right];   // 取左右子节点中较小的
        if (((Comparable<? super T>)x).compareTo((T) c) <= 0)
            break;           // x <= 最小子节点，堆性质满足，停止
        es[k] = c;
        k = child;
    }
    es[k] = x;
}
```

`peek()`（查看堆顶，O(1)）：直接返回 `queue[0]`。

**批量建堆（构造器传入 Collection，O(n)）**：
```java
// 不是逐个 offer，而是 heapify：从最后一个非叶子节点开始，逐个 siftDown
// 时间复杂度 O(n)，比逐个 offer O(n log n) 更快
private void heapify() {
    final Object[] es = queue;
    int n = size, i = (n >>> 1) - 1;  // 最后一个非叶子节点的下标
    for (; i >= 0; i--)
        siftDown(i, (E) es[i]);
}
```

**特点**：
- 默认最小堆。要最大堆：`new PriorityQueue<>(Comparator.reverseOrder())`
- 不允许 null 元素
- 不保证相同优先级元素的顺序
- 不是线程安全的（线程安全用 `PriorityBlockingQueue`）
- 扩容：容量 < 64 时翻倍，≥ 64 时增加 50%

**常用场景**：Top-K 问题、任务调度、Dijkstra/Prim 算法。

```java
// Top-K 最大值，用最小堆维护 K 个元素
PriorityQueue<Integer> minHeap = new PriorityQueue<>(k);
for (int num : nums) {
    minHeap.offer(num);
    if (minHeap.size() > k) minHeap.poll();  // 弹出最小，保留 K 个最大
}
```

---

### ArrayDeque

**底层**：循环数组（Circular Array）

```java
transient Object[] elements;
transient int head;   // 队头指针（指向第一个元素）
transient int tail;   // 队尾指针（指向下一个可插入位置）
// 容量始终是 2 的幂次，用于位运算取模
```

**循环数组的关键**：利用容量是 2 的幂次做快速取模：
```java
// addFirst：向前移动 head（不是 --head，而是循环）
head = (head - 1) & (elements.length - 1);  // 等价于 (head - 1 + length) % length
elements[head] = e;

// addLast：向后移动 tail
elements[tail] = e;
tail = (tail + 1) & (elements.length - 1);

// 满了（head == tail）触发扩容，容量翻倍，重新排列元素
```

**时间复杂度**：所有两端操作均 O(1) 均摊，random access O(n)（因为是循环的，下标不直连）。

**与 LinkedList 的比较**：
- ArrayDeque **在几乎所有场景都比 LinkedList 快**（数组连续内存，缓存友好；无节点对象开销）
- ArrayDeque 不能存 null（null 用作空槽标记）
- LinkedList 在不知道大小上限时理论上无限，ArrayDeque 也是（会扩容）

**初始容量**：默认 16，始终为 2 的幂次。

**当 Stack 用**：
```java
Deque<Integer> stack = new ArrayDeque<>();
stack.push(1);    // addFirst
stack.pop();      // removeFirst
stack.peek();     // peekFirst
```

**当 Queue 用**：
```java
Deque<Integer> queue = new ArrayDeque<>();
queue.offer(1);   // addLast
queue.poll();     // removeFirst
queue.peek();     // peekFirst
```

---

## 六、Map 实现类

### HashMap（重点）

**底层结构（Java 8+）**：数组 + 链表 + 红黑树

```java
transient Node<K,V>[] table;        // 桶数组（懒初始化，首次 put 才创建）
transient int size;                  // 键值对数量
int threshold;                       // 下次扩容的阈值 = capacity * loadFactor
final float loadFactor;              // 负载因子，默认 0.75
static final int DEFAULT_INITIAL_CAPACITY = 1 << 4;  // 16
static final int MAXIMUM_CAPACITY = 1 << 30;
static final float DEFAULT_LOAD_FACTOR = 0.75f;
static final int TREEIFY_THRESHOLD = 8;     // 单个桶内链表结点数 ≥8，满足转树候选
static final int UNTREEIFY_THRESHOLD = 6;   // 树结点≤6，退化为链表
static final int MIN_TREEIFY_CAPACITY = 64; // 允许树化的最低 table 数组长度


static class Node<K,V> implements Map.Entry<K,V> {
    final int hash;
    final K key;
    V value;
    Node<K,V> next;
}
```

**hash 函数**：

```java
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}
// 再用 (n - 1) & hash 定位桶下标（n 是容量，必须是 2 的幂次）
```

为什么要 `^ (h >>> 16)`（扰动函数）？

```
原始 hashCode：1111 1111 1111 1111  1010 1010 1010 1010
h >>> 16：     0000 0000 0000 0000  1111 1111 1111 1111
XOR 结果：     1111 1111 1111 1111  0101 0101 0101 0101
```

当容量较小时，`(n-1) & hash` 只用到 hash 的低位。扰动函数把高 16 位的信息混入低 16 位，让 hash 分布更均匀，减少碰撞。

**为什么容量必须是 2 的幂次？**

`(n-1) & hash` 等价于 `hash % n`（当 n 是 2 的幂次时），位运算比取模快得多。且扩容时可以用简单规则重新分配桶（见下文扩容）。

**put 流程**：

```
put(key, value)
    ↓
计算 hash = hash(key)
    ↓
table 为空？→ 初始化 table（默认 16 个桶）
    ↓
i = (n - 1) & hash，定位到桶 table[i]
    ↓
table[i] 为空？
  ├── 是 → 直接创建 Node 放入，结束
  └── 否 → 桶已有元素，处理碰撞
          ↓
      头节点 key 与新 key 相同（hash 相等且 equals）？
        ├── 是 → 更新 value，结束
        └── 否 → 是 TreeNode？
                  ├── 是 → 红黑树插入/更新
                  └── 否 → 遍历链表
                            ├── 找到相同 key → 更新 value
                            └── 到达链尾 → 尾插新 Node
                                          链表长度 >= 8 && table.length >= 64 → treeify
    ↓
size++ 后检查是否需要扩容（size > threshold）
```

**为什么 Java 8 改成尾插法**（Java 7 是头插）？

Java 7 头插法在多线程并发 resize 时，链表会形成环，导致 `get()` 陷入死循环。Java 8 改为尾插，避免了这个问题（但 HashMap 仍不是线程安全的，只是不会死循环了）。

**链表转红黑树（treeify）的条件**：

链表长度 >= `TREEIFY_THRESHOLD`（8）**且** 数组总长度 >= `MIN_TREEIFY_CAPACITY`（64）。

两个条件缺一不可：数组小时，优先扩容（扩容能把碰撞到同一桶的元素分散）；只有数组够大时才真正转树。

**为什么阈值是 8？**

在理想均匀 hash 情况下，桶中元素个数符合泊松分布，参数 λ = 0.5。链表长度为 8 的概率约为 0.00000006，极小概率。设为 8 是性能（链表短时遍历快）和极端情况（hash 碰撞严重时红黑树保底）的平衡点。

**扩容（resize）**：

```java
// 新容量 = 旧容量 × 2
// 每个元素重新定位：只需判断 hash & oldCapacity 是 0 还是 1
//   = 0 → 留在原桶 i
//   = 1 → 移到 i + oldCapacity

// 例：旧容量 16（0001 0000），扩容后 32
// hash & 16 = 0 → 桶下标不变
// hash & 16 = 1 → 桶下标 += 16
```

这个规律来自 2 的幂次扩容：`(32-1) & hash` 比 `(16-1) & hash` 只多了一位（第 5 位），而这一位正好是 `hash & oldCapacity` 的值。

**get 流程**：
```
get(key)
    ↓
hash = hash(key)，定位桶 table[(n-1) & hash]
    ↓
检查头节点 → hash 和 key 都匹配 → 返回
    ↓
头节点是 TreeNode → 红黑树查找 O(log n)
    ↓
链表遍历查找 O(k)（k = 链表长度）
```

**null 键**：null 的 hash 固定为 0，存储在 `table[0]` 对应的桶里。

**线程安全问题**：
- 多线程同时 put 可能导致数据丢失（两个线程都判断桶为空，都往同一位置插入，后者覆盖前者）
- 多线程 size++ 不是原子操作，结果不准确
- 多线程 resize 可能导致部分线程看到旧 table，丢失数据

---

### LinkedHashMap

**底层**：继承 HashMap，额外维护一个**双向链表**串联所有 Entry（按插入顺序或访问顺序）。

```java
// HashMap.Node 的扩展版
static class Entry<K,V> extends HashMap.Node<K,V> {
    Entry<K,V> before, after;   // 双向链表指针
}

transient LinkedHashMap.Entry<K,V> head;   // 最老的节点（链表头）
transient LinkedHashMap.Entry<K,V> tail;   // 最新的节点（链表尾）
final boolean accessOrder;  // false=插入顺序（默认），true=访问顺序
```

**访问顺序模式（LRU 缓存）**：

```java
// accessOrder=true 时，每次 get/put 都把该节点移到链表尾部（最新访问）
protected void afterNodeAccess(Node<K,V> e) {
    // 把节点移到 tail
}

// 插入新节点后回调，可以在这里实现自动淘汰
protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
    return false;  // 默认不删除
}
```

**实现 LRU Cache**：

```java
LinkedHashMap<Integer, Integer> lruCache = new LinkedHashMap<>(capacity, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
        return size() > capacity;   // 超过容量自动删除最久未访问的
    }
};
```

---

### TreeMap

**底层**：红黑树（Red-Black Tree）

```java
private transient Entry<K,V> root;   // 红黑树根节点

static final class Entry<K,V> implements Map.Entry<K,V> {
    K key;
    V value;
    Entry<K,V> left, right, parent;
    boolean color = BLACK;
}
```

**红黑树性质**：
1. 节点是红色或黑色
2. 根节点是黑色
3. 所有叶子节点（NIL）是黑色
4. 红色节点的两个子节点都是黑色（不能有连续红色）
5. 从任一节点到其叶子节点的所有路径包含相同数量的黑色节点

这些性质保证树的高度 ≤ 2log(n+1)，所有操作 O(log n)。

**特点**：
- `keySet()` 遍历按 key 升序
- 要求 key 可比较（实现 `Comparable` 或传入 `Comparator`）
- 不允许 null key（比较时 NPE），允许 null value
- 实现 `NavigableMap`，支持 `floorKey`/`ceilingKey`/`lowerKey`/`higherKey`/`subMap` 等范围查询

**与 HashMap 的选择**：
- 只需要查找 → HashMap（O(1)）
- 需要按 key 排序/范围查询 → TreeMap（O(log n)）

---

### WeakHashMap

**底层**：与 HashMap 类似，但 key 是弱引用（`WeakReference`）。

当 key 对象不再被其他地方强引用时，GC 会回收 key，并将对应 Entry 放入 `ReferenceQueue`。WeakHashMap 在每次操作时会先清理 `ReferenceQueue` 中已被 GC 的 Entry。

**适用场景**：缓存（key 是某些对象，对象消亡时缓存自动失效），如 `ClassLoader` 相关的元数据缓存。

---

### IdentityHashMap

key 的比较使用 `==`（引用相等）而非 `equals()`，hash 用 `System.identityHashCode()`（基于对象内存地址）。

**适用场景**：需要以对象身份（而非逻辑相等性）为 key 的场景，如序列化中记录已处理的对象。

---

### EnumMap

**底层**：数组，下标 = 枚举值的 ordinal（声明顺序）。

```java
private final Class<K> keyType;
private transient K[] keyUniverse;   // 枚举所有值
private transient Object[] vals;     // vals[ordinal] = value
```

**特点**：性能极佳（数组直接寻址），有序（按枚举声明顺序），不允许 null key，允许 null value。

---

### Hashtable（遗留，了解即可）

与 HashMap 主要区别：
- 所有公共方法都 `synchronized`（方法级锁，性能差）
- 不允许 null key 和 null value
- 初始容量 11，扩容系数 2n+1（不是 2 的幂次，hash 用 `%`）
- 继承 `Dictionary`（而非 `AbstractMap`）

**不推荐使用**，需要线程安全的 Map 用 `ConcurrentHashMap`。

---

## 七、并发集合

### ConcurrentHashMap

**Java 7 实现：Segment 分段锁**

```java
// 分成 concurrencyLevel（默认16）个 Segment
// 每个 Segment 是一个 ReentrantLock
// 写操作只锁单个 Segment，读操作无锁（volatile）
Segment<K,V>[] segments;

static final class Segment<K,V> extends ReentrantLock {
    transient volatile HashEntry<K,V>[] table;
}
```

问题：16 个 Segment 是固定的，高并发下竞争仍然激烈；`size()` 需要锁所有 Segment。

---

**Java 8 实现：CAS + synchronized（更细粒度）**

```java
transient volatile Node<K,V>[] table;   // volatile 保证可见性

// 写操作的粒度降低到单个桶：
// 1. 桶为空时用 CAS 插入（无锁）
// 2. 桶非空时用 synchronized 锁住桶的头节点
```

**put 流程**：
```
put(key, value)
    ↓
hash = spread(key.hashCode())   // 扰动函数
    ↓
table 为空 → initTable()（CAS + 自旋初始化，只有一个线程能初始化）
    ↓
定位桶 i = (n - 1) & hash
    ↓
table[i] 为 null？
  ├── 是 → CAS 插入新节点（无锁，失败则重试）
  └── 否 → 桶头节点的 hash == MOVED(-1)？
            ├── 是 → 正在 resize，帮助迁移数据（helpTransfer）
            └── 否 → synchronized(table[i]) {
                        链表/红黑树插入（与 HashMap 类似）
                      }
    ↓
addCount(1L, binCount)  // 更新 size（分布式计数）
检查是否需要扩容
```

**size() 的分布式计数（CounterCell）**：

```java
// 不用一个 AtomicLong，因为高并发下 CAS 竞争激烈
// 用一个 CounterCell 数组，每个线程更新不同的 Cell（减少竞争）
// size() = baseCount + sum(counterCells[i].value)
```

**扩容（transfer）**：

支持多线程并发扩容。table 被分成多个区间（stride），每个线程认领一段区间负责迁移，迁完后在原桶位置放一个 `ForwardingNode`（hash = MOVED），其他线程 put 时看到 `MOVED` 就协助迁移。

**与 HashMap 的核心区别**：

| | HashMap | ConcurrentHashMap |
|--|---------|-------------------|
| 线程安全 | 否 | 是（细粒度锁）|
| null key/value | 允许 | 不允许（无法区分 null 是"不存在"还是"值为 null"）|
| size() | 准确 | 近似值（高并发下）|
| 锁粒度 | 无锁 | CAS（空桶）/ synchronized（桶头）|

---

### CopyOnWriteArrayList

**原理**：读写分离，写时复制。

```java
private transient volatile Object[] array;

// 写操作：复制整个数组，修改副本，再原子替换引用
public boolean add(E e) {
    synchronized (lock) {
        Object[] es = getArray();
        int len = es.length;
        es = Arrays.copyOf(es, len + 1);   // 复制
        es[len] = e;                         // 修改副本
        setArray(es);                        // 原子替换
        return true;
    }
}

// 读操作：完全无锁，直接读快照
public E get(int index) {
    return elementAt(getArray(), index);
}
```

**特点**：
- 读操作完全无锁，性能极高，适合**读多写少**场景
- 写操作复制整个数组，O(n) 内存和时间开销
- **弱一致性**：读操作读的是某个时刻的快照，不保证读到最新写入
- 迭代器是快照，不会抛 `ConcurrentModificationException`，但也不反映并发写

**适用场景**：事件监听器列表（很少变更，频繁遍历）、白名单/黑名单（低频更新，高频读取）。

---

### BlockingQueue 体系

`BlockingQueue` 扩展 `Queue`，增加了阻塞语义：

| 操作 | 抛异常 | 返回特殊值 | 阻塞 | 超时 |
|------|--------|----------|------|------|
| 入队 | `add(e)` | `offer(e)` | `put(e)` | `offer(e, time, unit)` |
| 出队 | `remove()` | `poll()` | `take()` | `poll(time, unit)` |
| 查看 | `element()` | `peek()` | — | — |

**`put(e)`**：队满时阻塞当前线程，直到有空间。
**`take()`**：队空时阻塞当前线程，直到有元素。

**ArrayBlockingQueue**：

```java
// 有界，数组，单锁（一把 ReentrantLock，生产者消费者共用）
final Object[] items;
int takeIndex, putIndex, count;
final ReentrantLock lock;
private final Condition notEmpty;  // 消费者等待条件
private final Condition notFull;   // 生产者等待条件
```

**LinkedBlockingQueue**：

```java
// 可有界（默认 Integer.MAX_VALUE），链表
// 双锁（takeLock + putLock），生产者和消费者各自有锁，并发度更高
```

**PriorityBlockingQueue**：

无界优先队列（底层是堆），`put()` 不会阻塞（无界），`take()` 在空时阻塞。

**DelayQueue**：

存储实现了 `Delayed` 接口的元素，只有元素到期（`getDelay() <= 0`）才能被取出。适合定时任务调度。

**SynchronousQueue**：

容量为 0，`put()` 必须等待 `take()` 到来才能完成，相当于线程间的直接交接（Handoff）。`Executors.newCachedThreadPool()` 使用它。

---

### ConcurrentLinkedQueue

无锁队列，基于 **CAS + Michael-Scott 算法**。

```java
// 使用 head 和 tail 两个原子引用
// offer()：CAS 修改 tail.next
// poll()：CAS 修改 head
```

**特点**：无锁，高吞吐量，适合高并发无需阻塞语义的场景；`size()` 是 O(n) 的（要遍历链表）。

---

### ConcurrentSkipListMap / ConcurrentSkipListSet

**底层**：跳表（Skip List）

跳表是多层有序链表：底层包含所有元素，每向上一层以一定概率（通常 50%）选取部分元素，形成"索引层"。查找时从最高层开始，快速跳过大段元素：

```
第3层：1 ──────────────────── 9
第2层：1 ──────── 5 ──────── 9
第1层：1 ── 3 ── 5 ── 7 ── 9
第0层：1 ─ 2 ─ 3 ─ 4 ─ 5 ─ 6 ─ 7 ─ 8 ─ 9
```

**特点**：
- 所有操作 O(log n) 均摊
- **天然有序**（按 key 排序），支持范围查询
- 无锁（CAS 实现），并发性能好
- 比 `Collections.synchronizedSortedMap(new TreeMap<>())` 并发性能高得多

---

## 八、工具类

### Collections

```java
// 排序和搜索
Collections.sort(list);                      // 要求元素实现 Comparable
Collections.sort(list, comparator);
Collections.binarySearch(list, key);          // 前提：已排序
Collections.reverse(list);                    // 反转
Collections.shuffle(list);                    // 随机打乱
Collections.swap(list, i, j);

// 查找
Collections.min(collection) / Collections.max(collection);
Collections.frequency(collection, obj);       // 统计出现次数

// 填充和复制
Collections.fill(list, obj);                  // 用 obj 填充全部
Collections.copy(dest, src);                  // src 复制到 dest（dest.size() >= src.size()）
Collections.nCopies(n, obj);                  // 返回包含 n 个 obj 的不可变 List

// 包装为线程安全（性能差，不如并发集合）
List<T> syncList = Collections.synchronizedList(new ArrayList<>());
Map<K,V> syncMap = Collections.synchronizedMap(new HashMap<>());

// 包装为不可变（写操作抛 UnsupportedOperationException）
List<T> unmodifiable = Collections.unmodifiableList(list);

// 空集合（不可变单例，避免 null 返回）
Collections.emptyList() / emptySet() / emptyMap()

// 单元素集合
Collections.singleton(obj)          // Set
Collections.singletonList(obj)      // List
Collections.singletonMap(k, v)      // Map

// 频次统计
Collections.disjoint(c1, c2)        // 两集合是否无交集
```

### Arrays

```java
Arrays.sort(arr);                        // 基本类型用双轴快排，对象用 TimSort
Arrays.parallelSort(arr);                // 并行排序（大数组有优势）
Arrays.binarySearch(arr, key);           // 已排序数组的二分查找
Arrays.copyOf(arr, newLength);
Arrays.copyOfRange(arr, from, to);
Arrays.fill(arr, val);
Arrays.equals(arr1, arr2);              // 深度比较（对基本类型数组）
Arrays.deepEquals(arr1, arr2);          // 多维数组深度比较
Arrays.asList(arr);                     // 数组 → 固定大小 List（不能 add/remove）
Arrays.stream(arr);                     // 数组 → Stream
Arrays.toString(arr);                   // 格式化打印
```

---

## 九、迭代器与遍历

### fail-fast vs fail-safe

**fail-fast**：`ArrayList`、`HashMap` 等非并发集合，迭代期间检测到结构修改立即抛 `ConcurrentModificationException`（通过 `modCount` 检测）。

**fail-safe**：`CopyOnWriteArrayList`、`ConcurrentHashMap` 等并发集合，迭代在快照上进行，不抛异常，但可能读不到最新数据。

### 迭代期间安全删除

```java
// ✓ 正确：使用 Iterator.remove()
Iterator<String> it = list.iterator();
while (it.hasNext()) {
    if (it.next().startsWith("a")) it.remove();
}

// ✓ Java 8：removeIf()（内部也是迭代器）
list.removeIf(s -> s.startsWith("a"));

// ✓ 倒序遍历删除（用下标）
for (int i = list.size() - 1; i >= 0; i--) {
    if (list.get(i).startsWith("a")) list.remove(i);
}

// ✗ 错误：for-each 中直接 list.remove() → ConcurrentModificationException
for (String s : list) {
    if (s.startsWith("a")) list.remove(s);  // 抛异常
}
```

---

## 十、选型速查

### List

| 场景 | 推荐 |
|------|------|
| 通用，随机访问多 | `ArrayList` |
| 只在两端操作 | `ArrayDeque`（不需要 List 接口时）|
| 多线程读多写少 | `CopyOnWriteArrayList` |
| 多线程读写均衡 | `Collections.synchronizedList` 或换成并发安全结构 |

### Set

| 场景 | 推荐 |
|------|------|
| 通用去重 | `HashSet` |
| 去重且保持插入顺序 | `LinkedHashSet` |
| 去重且需要排序 | `TreeSet` |
| 枚举值去重 | `EnumSet` |

### Map

| 场景 | 推荐 |
|------|------|
| 通用键值存储 | `HashMap` |
| 需要保持插入顺序 | `LinkedHashMap` |
| LRU 缓存 | `LinkedHashMap(accessOrder=true)` |
| 需要按 key 排序 | `TreeMap` |
| 多线程 | `ConcurrentHashMap` |
| key 是枚举 | `EnumMap` |
| key 用引用相等比较 | `IdentityHashMap` |

### Queue / Deque

| 场景 | 推荐 |
|------|------|
| 普通队列 | `ArrayDeque` |
| 栈 | `ArrayDeque`（禁用 `Stack`）|
| 优先队列 | `PriorityQueue` |
| 多线程生产者消费者 | `LinkedBlockingQueue` / `ArrayBlockingQueue` |
| 多线程无阻塞队列 | `ConcurrentLinkedQueue` |
| 延迟任务 | `DelayQueue` |

---

## 十一、常见面试考点速查

| 问题 | 要点 |
|------|------|
| ArrayList 和 LinkedList 区别 | 数组 vs 链表；随机访问 O(1) vs O(n)；几乎所有场景 ArrayList 更快 |
| HashMap 的 hash 函数为何 `^ h>>>16` | 扰动函数，把高位混入低位，减少低容量时的碰撞 |
| HashMap 为何容量是 2 的幂次 | `(n-1) & hash` 等价取模，位运算更快；扩容时分配规律简单 |
| HashMap 链表转红黑树的条件 | 链表长度 >= 8 **且** 数组长度 >= 64，两个条件都要满足 |
| HashMap 的负载因子为何是 0.75 | 时间和空间的折中：0.75 时 hash 冲突概率适中，内存利用率也不低 |
| Java 7 头插法的问题 | 并发 resize 时链表成环，get 死循环；Java 8 改为尾插 |
| ConcurrentHashMap 和 Hashtable 区别 | 锁粒度：全表锁 vs 桶级锁/CAS；null：Hashtable 不允许 |
| CopyOnWriteArrayList 适用场景 | 读多写少；迭代期间不抛异常；写代价高（O(n) 复制）|
| PriorityQueue 是最大堆还是最小堆 | 默认最小堆；最大堆传 `Comparator.reverseOrder()` |
| ArrayDeque 为何比 LinkedList 快 | 数组连续内存，缓存友好；无节点对象分配开销 |
| fail-fast 的原理 | modCount 机制，迭代器记录创建时的 modCount，每次 next 比较 |
| TreeMap 和 HashMap 区别 | 红黑树 O(log n) vs 哈希 O(1)；有序 vs 无序；TreeMap 支持范围查询 |
| HashMap 和 HashSet 关系 | HashSet 底层就是 HashMap，元素作 key，PRESENT 作 value |
| BlockingQueue 的 put 和 offer 区别 | put 满了阻塞；offer 满了返回 false（可加超时参数）|
| ConcurrentHashMap size() 为何不准确 | 计数用 CounterCell 分散，size() 是 baseCount + Σcells，高并发下有竞态 |
| 跳表的优势 | 有序 + 并发友好（无锁 CAS），比加锁的 TreeMap 并发性能好 |
## 十二、集合间互转

大多数集合构造器都接受 `Collection<? extends E>` 参数，所以 List、Set、Queue/Deque 之间基本可以直接互转。Map 不继承 Collection，需要额外处理。

### List ↔ Set

```java
List<String> list = List.of("a", "b", "a", "c");

// List → HashSet（去重，无序）
Set<String> set = new HashSet<>(list);

// List → LinkedHashSet（去重，保留插入顺序）
Set<String> set = new LinkedHashSet<>(list);

// List → TreeSet（去重，自然排序）
Set<String> set = new TreeSet<>(list);   // 元素须实现 Comparable

// Set → ArrayList
List<String> list = new ArrayList<>(set);

// Set → LinkedList
List<String> list = new LinkedList<>(set);
```

**注意**：List 转 Set 会丢失重复元素；Set 转 List 顺序取决于原 Set 类型。

### List / Set ↔ Queue / Deque

```java
List<Integer> list = List.of(1, 2, 3);

// List → ArrayDeque
Deque<Integer> deque = new ArrayDeque<>(list);

// List → PriorityQueue（自动建堆，O(n)）
Queue<Integer> pq = new PriorityQueue<>(list);

// ArrayDeque → ArrayList
List<Integer> list = new ArrayList<>(deque);
```

### 数组 ↔ List

```java
String[] arr = {"a", "b", "c"};

// 数组 → List（固定大小，不能 add/remove，但可以 set）
List<String> fixed = Arrays.asList(arr);
fixed.add("d");        // 抛 UnsupportedOperationException
fixed.set(0, "X");     // OK

// 数组 → 可变 List
List<String> mutable = new ArrayList<>(Arrays.asList(arr));

// Java 9+ 简写（不可变）
List<String> immutable = List.of(arr);

// List → 数组
String[] arr = list.toArray(new String[0]);
// toArray() 无参返回 Object[]，无法直接强转为 String[]，必须传类型
Object[] objArr = list.toArray();   // OK 但类型是 Object[]
```

**`Arrays.asList` 的三个坑**：

```java
// 坑1：返回的是固定大小 List，add/remove 抛异常
List<String> list = Arrays.asList("a", "b");
list.add("c");   // UnsupportedOperationException

// 坑2：修改原数组会影响 List（共享底层数组）
String[] arr = {"a", "b"};
List<String> list = Arrays.asList(arr);
arr[0] = "X";
System.out.println(list.get(0));   // "X"

// 坑3：基本类型数组不能直接用，会整体当一个元素
int[] ints = {1, 2, 3};
List list = Arrays.asList(ints);   // List<int[]>，只有一个元素！
// 正确做法：
List<Integer> list = Arrays.stream(ints).boxed().collect(Collectors.toList());
```

### Collection ↔ Map

集合和 Map 没有直接互转方法，需要 Stream 或手动处理：

```java
// List → Map（以元素本身为 key，配对某个值）
List<String> list = List.of("apple", "banana", "cherry");
Map<String, Integer> map = list.stream()
    .collect(Collectors.toMap(s -> s, String::length));
// {"apple"=5, "banana"=6, "cherry"=6}

// List<对象> → Map（常用：id 作 key）
Map<Long, User> userMap = users.stream()
    .collect(Collectors.toMap(User::getId, u -> u));

// Map.keySet() → Set（视图，不可独立修改）
Set<String> keys = map.keySet();

// Map.values() → Collection（视图）
Collection<Integer> values = map.values();

// Map.entrySet() → Set<Map.Entry>
Set<Map.Entry<String, Integer>> entries = map.entrySet();

// Map → List（key 列表或 value 列表）
List<String> keyList = new ArrayList<>(map.keySet());
List<Integer> valueList = new ArrayList<>(map.values());
```

### 不可变集合

```java
// Java 9+ 工厂方法，创建不可变集合
List<String> list = List.of("a", "b", "c");
Set<String> set = Set.of("a", "b", "c");
Map<String, Integer> map = Map.of("a", 1, "b", 2);

// 任何修改都抛 UnsupportedOperationException
list.add("d");   // 抛异常

// Collections 工具类包装（视图，不可变但元素本身可变）
List<String> unmodifiable = Collections.unmodifiableList(mutableList);
```

### 互转速查表

| 目标类型 | 写法 | 注意 |
|---------|------|------|
| `ArrayList` | `new ArrayList<>(collection)` | 通用，任何 Collection 都行 |
| `HashSet` | `new HashSet<>(collection)` | 自动去重 |
| `LinkedHashSet` | `new LinkedHashSet<>(collection)` | 去重且保顺序 |
| `TreeSet` | `new TreeSet<>(collection)` | 去重且排序，元素须 Comparable |
| `ArrayDeque` | `new ArrayDeque<>(collection)` | 通用 |
| `PriorityQueue` | `new PriorityQueue<>(collection)` | 自动堆化 |
| `数组` | `list.toArray(new T[0])` | 必须传类型参数 |
| `可变 List` | `new ArrayList<>(Arrays.asList(arr))` | 不要直接用 Arrays.asList |
| `Map` | `stream().collect(Collectors.toMap(...))` | 需指定 key/value 映射函数 |

---

## 十三、实战：LRU Cache 手写（LeetCode 146）

LRU（Least Recently Used）缓存是集合框架知识的综合应用，考察 LinkedHashMap 原理 + 双向链表操作 + API 细节。

---

### 13.1 核心数据结构选择

**目标**：get/put 都是 O(1)，且能快速定位"最久未使用"的 key。

**方案**：HashMap（O(1) 查找）+ 双向链表（O(1) 调整顺序）

```
队头 = 最久未使用（下次淘汰的候选）
队尾 = 最近刚使用

get 命中 → 把该节点移到队尾
put 新 key → 插到队尾；若已满先删队头节点
put 已有 key → 更新值，移到队尾
```

---

### 13.2 常见 API 陷阱（重要）

**陷阱 1：`List.get(x)` 参数是下标，不是元素值**

```java
List<Integer> list = new LinkedList<>();
list.add(5);
list.get(5);   // ❌ 下标越界！get 接收的是 index，不是元素值
list.contains(5);  // ✓ 判断是否包含元素 5
```

**陷阱 2：`remove(int)` 和 `remove(Object)` 的重载歧义**

```java
LinkedList<Integer> q = new LinkedList<>();
q.add(2);
q.remove(2);           // ❌ 匹配 remove(int index)，删下标 2，越界！
q.remove((Integer) 2); // ✓ 匹配 remove(Object o)，删值为 2 的元素
```

int 字面量优先匹配 `remove(int index)`，必须手动装箱才能调用 `remove(Object)`。

**陷阱 3：`List` 接口没有 `getFirst()` / `removeFirst()`**

```java
List<Integer> q = new LinkedList<>();
q.getFirst();   // ❌ 编译报错，List 接口没有这个方法

LinkedList<Integer> q = new LinkedList<>();
q.getFirst();   // ✓ LinkedList 作为 Deque 实现，有这些方法
```

变量类型声明为 `List` 时，编译器只认 List 接口的方法，看不到 Deque 的方法。

**陷阱 4：淘汰时 map 和链表两边都要删**

```java
// ❌ 只删 map，链表里的 key 还在，数据不一致
map.remove(oldestKey);

// ✓ 两边都删
int oldestKey = queue.getFirst();
queue.removeFirst();
map.remove(oldestKey);
```

**陷阱 5：get 不到 key 时不能动队列**

```java
// ❌ key 不存在也往队列塞，导致队列里有 map 没有的 key
public int get(int key) {
    queue.add(key);   // 错！
    return map.getOrDefault(key, -1);
}

// ✓ 先判断存在再操作
public int get(int key) {
    if (!map.containsKey(key)) return -1;
    queue.remove((Integer) key);
    queue.addLast(key);
    return map.get(key);
}
```

**陷阱 6：不要自己维护 size 计数器**

自己写 `now++` / `now--` 在各种分支里极易漏掉同步，直接用 `map.size()` 即可。

---

### 13.3 O(n) 版本：LinkedList + HashMap（理解思路，会超时）

用 LinkedList 作时序队列，逻辑清晰但 `remove(Object)` 是 O(n)，大数据用例会超时。**仅用来验证思路，面试不能写这个。**

```java
import java.util.*;

class LRUCache {
    Map<Integer, Integer> map = new HashMap<>();
    LinkedList<Integer> queue = new LinkedList<>();  // 必须声明为 LinkedList 才能用 getFirst()
    int capacity;

    public LRUCache(int capacity) {
        this.capacity = capacity;
    }

    public int get(int key) {
        if (!map.containsKey(key)) return -1;
        // 移到队尾（标记为最近使用）
        queue.remove((Integer) key);   // O(n)
        queue.addLast(key);
        return map.get(key);
    }

    public void put(int key, int value) {
        if (map.containsKey(key)) {
            // key 已存在：更新值，挪到队尾
            map.put(key, value);
            queue.remove((Integer) key);   // O(n)
            queue.addLast(key);
            return;
        }
        // key 不存在：满了先淘汰队头
        if (map.size() >= capacity) {
            int oldest = queue.removeFirst();
            map.remove(oldest);
        }
        map.put(key, value);
        queue.addLast(key);
    }
}
```

**为什么 O(n)？** LinkedList 只是普通双向链表，没有存"key → 节点引用"的映射，`remove(Object)` 必须从头遍历找到那个节点。

---

### 13.4 O(1) 版本：自定义双向链表 + HashMap（面试标准写法）

HashMap 存 `key → Node`，通过 Node 引用 O(1) 定位链表节点。用虚拟头尾节点（dummy）简化边界处理。

```java
import java.util.HashMap;

class LRUCache {

    static class Node {
        int key, val;
        Node prev, next;
        Node(int k, int v) { key = k; val = v; }
    }

    private final HashMap<Integer, Node> map;
    private final Node dummyHead, dummyTail;   // 虚拟头尾，不存实际数据
    private final int cap;

    public LRUCache(int capacity) {
        cap = capacity;
        map = new HashMap<>();
        dummyHead = new Node(-1, -1);
        dummyTail = new Node(-1, -1);
        dummyHead.next = dummyTail;
        dummyTail.prev = dummyHead;
    }

    // 从链表中摘除节点（O(1)，因为有 prev/next 直接操作）
    private void detach(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    // 插入到队尾（dummyTail 前面）
    private void insertToTail(Node node) {
        Node pre = dummyTail.prev;
        pre.next = node;
        node.prev = pre;
        node.next = dummyTail;
        dummyTail.prev = node;
    }

    // 移到队尾 = 先摘除 + 再插尾
    private void moveToTail(Node node) {
        detach(node);
        insertToTail(node);
    }

    public int get(int key) {
        if (!map.containsKey(key)) return -1;
        Node node = map.get(key);
        moveToTail(node);   // O(1)
        return node.val;
    }

    public void put(int key, int value) {
        if (map.containsKey(key)) {
            Node node = map.get(key);
            node.val = value;
            moveToTail(node);   // O(1)
            return;
        }
        if (map.size() >= cap) {
            // 淘汰队头（dummyHead 后面的第一个真实节点）
            Node oldest = dummyHead.next;
            detach(oldest);           // O(1)
            map.remove(oldest.key);
        }
        Node newNode = new Node(key, value);
        insertToTail(newNode);   // O(1)
        map.put(key, newNode);
    }
}
```

**关键：Node 里存了 key**，淘汰时能直接从 Node 拿到 key 去删 map，不需要反查。

---

### 13.5 JDK 内置方案：LinkedHashMap（生产代码可用）

```java
class LRUCache extends LinkedHashMap<Integer, Integer> {
    private final int capacity;

    public LRUCache(int capacity) {
        super(capacity, 0.75f, true);   // accessOrder=true：访问顺序模式
        this.capacity = capacity;
    }

    public int get(int key) {
        return super.getOrDefault(key, -1);
    }

    public void put(int key, int value) {
        super.put(key, value);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
        return size() > capacity;   // 超容量自动删最老（队头）
    }
}
```

面试通常要求手写双向链表版（12.4），生产代码用这个。

---

### 13.6 踩坑总结

> `get(index)` 语义、`remove(int)` 重载、`List` 接口 vs `LinkedList` 声明这三条已整理到**三、List 实现类**对应章节。

| 陷阱 | 错误写法 | 正确写法 |
|------|---------|---------|
| 两个数据结构只删一边 | `map.remove(k)`（忘删链表） | `detach(node); map.remove(k)` 两边都删 |
| 查询 miss 时产生副作用 | `get` 时无条件 `moveToTail(key)` | 先 `map.containsKey(key)` 判断，miss 直接返回 -1 |
| 自维护 size 计数器 | `now++` / `now--` 手动维护 | 直接用 `map.size()`，以 map 为唯一数据源 |