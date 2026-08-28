# Java String 完全详解

---

## 一、String 的底层实现

### Java 8 及之前

```java
public final class String implements Serializable, Comparable<String>, CharSequence {
    private final char[] value;   // 字符串内容，UTF-16 编码，每个 char 占 2 字节
    private int hash;             // 缓存 hashCode，默认 0，惰性计算
}
```

- 每个字符占 2 字节（UTF-16），即使全是 ASCII 也浪费一半空间

### Java 9+ Compact Strings（压缩字符串）

```java
public final class String implements Serializable, Comparable<String>, CharSequence {
    private final byte[] value;   // 字节数组，编码由 coder 决定
    private final byte coder;     // LATIN1 = 0（1字节/字符），UTF16 = 1（2字节/字符）
    private int hash;
}
```

**核心优化**：若字符串所有字符都在 Latin-1 范围（0x00~0xFF，即 ASCII 及西欧字符），就用 1 字节/字符存储；否则退化为 UTF-16（2 字节/字符）。

对于全 ASCII 的字符串（绝大多数英文代码、日志），内存占用减少 **50%**，缓存命中率显著提升。

**对开发者透明**：API 完全不变，JVM 内部自动判断并选择编码。

### 为什么 String 是 final 的？

1. **不可变性保证**：`class` 是 `final` 防止子类覆盖方法破坏不可变约束；`value` 是 `final` 防止数组引用被替换
2. **字符串常量池可行的前提**：多个引用可以安全地共享同一个 String 对象，若允许修改，共享就会产生并发问题
3. **线程安全**：不可变对象天然线程安全，可以在多线程间共享而不需要同步
4. **hashCode 可缓存**：内容不变，hash 只需计算一次，之后从 `hash` 字段直接读取

---

## 二、字符串常量池（String Pool）

### 是什么

字符串常量池是 JVM 维护的一块**特殊存储区域**，用于缓存字符串字面量，避免重复创建相同内容的 String 对象。

**位置变迁**：
- Java 6 及之前：位于永久代（PermGen），大小固定，易 OOM
- **Java 7+：移到堆（Heap）中**，由 GC 统一管理，大小随堆动态变化

```
堆（Heap）
├── 字符串常量池（String Pool）
│   ├── "hello" → String 对象
│   ├── "world" → String 对象
│   └── ...
└── 普通堆区
    ├── new String("hello") 产生的对象
    └── 其他对象
```

### 字面量 vs new String()

**字面量**：编译期确定，自动进入常量池

```java
String s1 = "hello";   // 常量池中创建（如不存在）或复用
String s2 = "hello";   // 直接复用常量池中已有的同一对象

System.out.println(s1 == s2);       // true：指向同一对象
System.out.println(s1.equals(s2));  // true
```

**new String()**：运行期在堆上创建新对象，不进常量池

```java
String s3 = new String("hello");   // 堆上新建一个 String 对象
String s4 = new String("hello");   // 再新建一个，与 s3 不同

System.out.println(s3 == s4);       // false：两个不同对象
System.out.println(s3.equals(s4));  // true：内容相同
System.out.println(s1 == s3);       // false：一个在池，一个在堆
```

**`new String("hello")` 到底创建了几个对象？**

- 若常量池中 `"hello"` 已存在：**1 个**（堆上的新对象）
- 若常量池中 `"hello"` 不存在：**2 个**（常量池中的 + 堆上的新对象）

### 运行期生成的字符串

字符串拼接、方法返回的字符串在运行期生成，**不会自动进入常量池**：

```java
String a = "hel";
String b = "lo";
String c = a + b;            // 运行期拼接，在堆上产生新对象
System.out.println(c == "hello");   // false

// 但编译器能推导的常量拼接，编译期直接折叠为字面量：
final String a2 = "hel";
final String b2 = "lo";
String c2 = a2 + b2;         // 编译期折叠为 "hello"，进常量池
System.out.println(c2 == "hello");  // true
```

---

## 三、intern() 方法

### 作用

手动将字符串放入常量池，并返回常量池中该字符串的引用。

```java
public native String intern();  // native 方法，JVM 实现
```

**逻辑**：
1. 查常量池是否已有内容相同的字符串
2. 若有，直接返回常量池中那个对象的引用
3. 若无，把当前字符串**的引用（Java 7+）**放入常量池，返回该引用

```java
String s1 = new String("hello");  // s1 指向堆上对象，常量池中已有 "hello"
String s2 = s1.intern();          // 返回常量池中 "hello" 的引用

System.out.println(s1 == s2);      // false：s1 在堆，s2 在池
System.out.println(s2 == "hello"); // true：都指向常量池同一对象
```

**Java 6 vs Java 7+ 的行为差异**：
- Java 6：intern() 会在 PermGen 的常量池中复制一份字符串对象（两个对象）
- Java 7+：常量池在堆中，intern() 直接把堆上已有的对象引用放入池，无需复制（池中放的是引用，不复制）

```java
// Java 7+ 特有现象
String s = new String("a") + new String("b");  // 堆上产生 "ab" 对象，池中没有 "ab"
String interned = s.intern();                  // 把 s 这个堆对象的引用直接放进池

System.out.println(s == interned);             // true（Java 7+）：池中就是 s 本身
System.out.println(s == "ab");                 // true（Java 7+）：字面量 "ab" 此时才入池，入的就是 s
```

### 使用场景

大量重复字符串（如从数据库/网络读取的相同城市名、状态值），intern 后共享一个对象，节省内存：

```java
// 解析 100 万条记录，city 字段只有几十种取值
String city = record.getCity().intern();  // 所有相同城市名共享同一对象
```

---

## 四、字符串比较

### == vs equals vs compareTo

```java
String s1 = "hello";
String s2 = "hello";
String s3 = new String("hello");

// == 比较引用地址
s1 == s2        // true（同一常量池对象）
s1 == s3        // false（s3 在堆）

// equals 比较内容（String 重写了 equals）
s1.equals(s3)   // true

// equalsIgnoreCase 忽略大小写
"Hello".equalsIgnoreCase("hello")   // true

// compareTo 按字典序，返回差值
"abc".compareTo("abd")   // 负数（c < d）
"abc".compareTo("abc")   // 0（相等）
"abd".compareTo("abc")   // 正数（d > c）
// compareToIgnoreCase 忽略大小写版本
```

**黄金原则：比较字符串内容永远用 `equals()`，不用 `==`。**

把常量放前面防止 NullPointerException：

```java
// 错误：若 status 为 null，抛 NPE
if (status.equals("active")) { ... }

// 正确：常量在前
if ("active".equals(status)) { ... }
```

---

## 五、常用方法详解

### 长度与字符访问

```java
String s = "Hello, World!";

s.length()          // 13：字符数量（不是字节数）
s.isEmpty()         // false：length() == 0
s.isBlank()         // false：Java 11+，全为空白字符（空格/tab/换行）才 true
s.charAt(0)         // 'H'：取指定下标字符，越界抛 StringIndexOutOfBoundsException
s.codePointAt(0)    // 72：取 Unicode 码点（处理 emoji/生僻字时用 codePoint 而非 char）
s.toCharArray()     // ['H','e','l','l','o',',','W','o','r','l','d','!']
```

### 查找与索引

```java
String s = "banana";

// indexOf：第一次出现的位置，找不到返回 -1
s.indexOf('a')         // 1
s.indexOf('a', 2)      // 3：从下标2开始找
s.indexOf("an")        // 1：查找子串
s.indexOf("an", 2)     // 3：从下标2开始找子串

// lastIndexOf：最后一次出现的位置
s.lastIndexOf('a')     // 5
s.lastIndexOf('a', 4)  // 3：从下标4往前找

// contains：是否包含子串（底层用 indexOf 实现）
s.contains("nan")      // true
```

### 截取与拼接

```java
String s = "Hello, World!";

// substring
s.substring(7)          // "World!"：从下标7到末尾
s.substring(7, 12)      // "World"：[7, 12) 左闭右开

// concat：拼接（不如 + 和 StringBuilder 常用）
"Hello".concat(", World")   // "Hello, World"

// join：Java 8+，用分隔符连接多个字符串
String.join(", ", "a", "b", "c")           // "a, b, c"
String.join("-", List.of("2024", "01", "01"))  // "2024-01-01"
```

### 判断前缀/后缀

```java
"hello.java".startsWith("hello")   // true
"hello.java".startsWith("java", 6) // true：从下标6开始判断
"hello.java".endsWith(".java")     // true
```

### 大小写转换

```java
"Hello World".toUpperCase()   // "HELLO WORLD"
"Hello World".toLowerCase()   // "hello world"
// 注意：对含有国际化字符的字符串，最好传 Locale：
"istanbul".toUpperCase(Locale.ENGLISH)   // "ISTANBUL"（避免土耳其语 I 问题）
```

### 去除空白

```java
"  hello  ".trim()    // "hello"：去除首尾 ASCII 空白（\t \n \r 空格）
"  hello  ".strip()   // "hello"：Java 11+，去除 Unicode 意义上的空白（更推荐）
"  hello  ".stripLeading()   // "hello  "：只去头
"  hello  ".stripTrailing()  // "  hello"：只去尾
```

**`trim()` vs `strip()`**：`trim()` 只去 ASCII 空白（`<= ' '`），`strip()` 基于 Unicode `Character.isWhitespace()`，能处理全角空格等。

### 替换

```java
String s = "aababc";

// replace：按字面值替换（不是正则）
s.replace('a', 'x')         // "xxbxbc"：替换字符
s.replace("ab", "X")        // "aXXc"：替换子串（所有出现）

// replaceFirst / replaceAll：正则替换
"a1b2c3".replaceFirst("\\d", "X")   // "aXb2c3"：只替换第一个
"a1b2c3".replaceAll("\\d", "X")     // "aXbXcX"：替换所有
// 注意：replaceAll 的第一个参数是正则，特殊字符要转义
"a.b.c".replaceAll("\\.", "-")       // "a-b-c"
```

### 分割

```java
// split：按正则分割，返回 String[]
"a,b,c".split(",")          // ["a", "b", "c"]
"a,,b".split(",")           // ["a", "", "b"]：中间空串保留
"a,,b,,".split(",")         // ["a", "", "b"]：末尾连续空串默认去掉
"a,,b,,".split(",", -1)     // ["a", "", "b", "", ""]：limit=-1 保留所有

// 分割时注意正则元字符要转义
"a.b.c".split("\\.")        // ["a", "b", "c"]（. 是正则通配符，需转义）
"a|b|c".split("\\|")        // ["a", "b", "c"]（| 也是正则元字符）

// 性能技巧：固定单字符分隔符用 indexOf 手动切割比 split 快（避免正则编译开销）
```

### 格式化

```java
// String.format（类似 C 的 printf）
String.format("Hello, %s! You are %d years old.", "Alice", 25)
// "Hello, Alice! You are 25 years old."

// 常用格式符
// %s  字符串    %d  整数    %f  浮点数    %n  换行（跨平台）
// %.2f  保留2位小数    %05d  5位宽度补零    %-10s  左对齐10宽

// Java 15+ formatted()（实例方法，等价于 String.format）
"Hello, %s!".formatted("World")   // "Hello, World!"
```

### 转换方法

```java
// String → 基本类型
Integer.parseInt("123")         // 123
Double.parseDouble("3.14")      // 3.14
Boolean.parseBoolean("true")    // true

// 基本类型 → String
String.valueOf(123)             // "123"（推荐，null 安全，返回 "null"）
Integer.toString(123)           // "123"
123 + ""                        // "123"（不推荐，会创建 StringBuilder）

// String ↔ char[]
char[] chars = "hello".toCharArray();
String s = new String(chars);

// String ↔ byte[]（涉及编码）
byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
String s = new String(bytes, StandardCharsets.UTF_8);
// 一定要显式指定 Charset，不要用无参版本（依赖平台默认编码，Windows 可能是 GBK）
```

### 正则相关

```java
// matches：整串匹配正则（等价于 Pattern.matches）
"12345".matches("\\d+")          // true
"123a5".matches("\\d+")          // false

// 预编译 Pattern（高频调用时必须用，避免重复编译正则）
Pattern pattern = Pattern.compile("\\d+");
Matcher matcher = pattern.matcher("abc123def456");
while (matcher.find()) {
    System.out.println(matcher.group());  // "123"、"456"
}
```

### 其他实用方法

```java
// repeat：Java 11+，重复字符串
"ab".repeat(3)              // "ababab"

// chars()：返回 IntStream，可流式处理每个字符
"hello".chars()
    .filter(c -> c != 'l')
    .forEach(c -> System.out.print((char) c));  // "heo"

// intern()：见第三节

// String.valueOf(null)：返回 "null" 字符串（不抛 NPE）
// null + ""：返回 "null"（不推荐，混淆语义）
```

---

## 六、字符串拼接底层原理

### + 运算符

Java 编译器将 `+` 翻译为 `StringBuilder`：

```java
// 源码
String s = a + b + c;

// 编译后等价于（Java 8）
String s = new StringBuilder().append(a).append(b).append(c).toString();
```

**循环中的陷阱**：

```java
// 错误：每次循环创建一个新 StringBuilder，性能 O(n²)
String result = "";
for (String item : list) {
    result += item;   // 每次都 new StringBuilder() + toString()
}

// 正确：一个 StringBuilder 复用
StringBuilder sb = new StringBuilder();
for (String item : list) {
    sb.append(item);
}
String result = sb.toString();
```

**Java 9+ invokedynamic 优化**：编译器生成 `invokedynamic` 指令，由 JVM 在运行时选择最优的拼接策略（不一定是 StringBuilder），且避免了中间 `toString()` 开销，比 Java 8 更快。

### 编译期常量折叠

两个字面量相加，编译器直接折叠，**不走 StringBuilder**：

```java
String s = "hello" + " " + "world";   // 编译期直接变成 "hello world"
```

---

## 七、StringBuilder vs StringBuffer vs String

| | `String` | `StringBuilder` | `StringBuffer` |
|--|---------|----------------|----------------|
| **可变性** | 不可变 | 可变 | 可变 |
| **线程安全** | 安全（不可变） | 不安全 | 安全（方法加 `synchronized`） |
| **性能** | 拼接慢（每次新建） | 最快 | 比 StringBuilder 慢（锁开销） |
| **适用场景** | 不需要修改的字符串 | 单线程字符串拼接 | 多线程共享的拼接（几乎不用） |

**实际建议**：99% 情况用 `StringBuilder`，多线程场景用 `ThreadLocal<StringBuilder>` 或重新设计，不要用 `StringBuffer`。

### StringBuilder 核心方法

```java
StringBuilder sb = new StringBuilder();

sb.append("hello")          // 追加，支持所有基本类型 + Object + char[]
sb.insert(0, "world ")      // 在指定位置插入
sb.delete(0, 5)             // 删除 [0, 5) 的字符
sb.deleteCharAt(3)          // 删除指定位置字符
sb.replace(0, 5, "Java")    // 替换 [0, 5) 为新字符串
sb.reverse()                // 反转整个字符串（回文检测常用）
sb.indexOf("ll")            // 查找子串
sb.length()                 // 当前字符数
sb.setCharAt(0, 'H')        // 修改指定位置字符
sb.toString()               // 转为 String

// 初始容量优化：预估长度传入构造器，避免扩容
StringBuilder sb2 = new StringBuilder(1024);
// 底层同样是 char[]/byte[]，默认容量 16，扩容策略 oldCapacity * 2 + 2
```

---

## 八、String 的 hashCode

```java
// String.hashCode() 源码
public int hashCode() {
    int h = hash;
    if (h == 0 && value.length > 0) {
        // s[0]*31^(n-1) + s[1]*31^(n-2) + ... + s[n-1]
        for (char c : value) {
            h = 31 * h + c;
        }
        hash = h;
    }
    return h;
}
```

**为什么是 31**：
- 质数，减少哈希碰撞
- `31 * h` = `(32 - 1) * h` = `h << 5 - h`，JIT 可用位移优化，比乘法快
- 实验表明 31 对英文词典的碰撞率较低

**hashCode 缓存**：第一次计算后存入 `hash` 字段，之后直接返回（不可变对象才能这样做）。空字符串 hashCode 为 0，所以 `hash == 0` 即"未计算"的标志（极小概率真正的 hash 是 0，此时每次都重算，可接受）。

---

## 九、面试高频题

### Q1：String 为什么设计成不可变的？

三个维度：
1. **安全**：字符串大量用于文件路径、网络连接参数、HashMap key——不可变才能防止持有者在传入后偷改
2. **性能**：hashCode 可缓存；常量池可共享，节省内存
3. **并发**：天然线程安全，无需同步

### Q2：`String s = new String("abc")` 创建了几个对象？

看常量池是否已有 `"abc"`：
- 已有：**1 个**（堆上的 new String 对象）
- 没有：**2 个**（常量池一个 + 堆上一个）

面试补充：若代码中其他地方出现过 `"abc"` 字面量，常量池在类加载时就已经有了，此时只创建 1 个。

### Q3：`==` 和 `equals` 的区别

- `==`：比较引用（内存地址）
- `equals`：`Object` 的默认实现也是 `==`；`String` 重写了，比较字符内容
- 永远用 `equals` 比较字符串内容

### Q4：String、StringBuilder、StringBuffer 的区别

见第七节表格。核心：可变性 + 线程安全 + 性能三维度。

### Q5：以下代码输出什么？

```java
String s1 = "hello";
String s2 = "world";
String s3 = "helloworld";
String s4 = s1 + s2;           // 运行期拼接，堆上新对象
String s5 = "hello" + "world"; // 编译期折叠为 "helloworld"，进常量池

System.out.println(s3 == s4);  // false：s4 在堆
System.out.println(s3 == s5);  // true：s5 和 s3 都是常量池同一对象
System.out.println(s3.equals(s4)); // true
```

### Q6：intern 的作用和 Java 7 前后的区别

见第三节。关键点：Java 7+ 常量池在堆上，intern 直接将堆对象引用放入池，不复制。

### Q7：字符串常量池在哪里？

Java 7 之前在永久代（PermGen），Java 7+ 移到了堆（Heap）中。

### Q8：如何统计字符串中某字符出现次数？

```java
// 方法一：toCharArray 遍历
long count = "banana".chars().filter(c -> c == 'a').count();  // 3

// 方法二：replace 差值
String s = "banana";
int count = s.length() - s.replace("a", "").length();  // 3

// 方法三：split
int count = "banana".split("a", -1).length - 1;  // 3
```

### Q9：如何反转字符串？

```java
// StringBuilder.reverse()（推荐）
new StringBuilder("hello").reverse().toString();  // "olleh"

// 注意：含 Emoji 或代理对（surrogate pair，码点 > 0xFFFF）的字符串
// reverse() 会错乱，需要按码点反转：
String s = "Hello😊";
int[] codePoints = s.codePoints().toArray();
// 反转 codePoints 数组后再 new String(codePoints, 0, len)
```

### Q10：字符串去重（大量重复字符串节省内存）

```java
// 方案1：intern()（简单，但 String Pool 的 GC 开销要注意）
String city = record.getCity().intern();

// 方案2：Java 8u20+ G1 GC 的 String Deduplication
// JVM 参数：-XX:+UseStringDeduplication（仅 G1 GC 支持）
// G1 在 GC 时自动检测内容相同的 String，让它们共享同一个 byte[] value
// 无需代码改动，但只合并底层数组，不合并 String 对象本身（和 intern 不同）
```

---

## 十、关键概念速查

| 概念 | 一句话说明 |
|------|-----------|
| 不可变性 | `class final` + `value final`，内容创建后不能改变 |
| Compact Strings | Java 9+，纯 Latin-1 字符串用 1 字节/字符，节省 50% 内存 |
| 字符串常量池 | JVM 维护的字符串缓存，Java 7+ 在堆中，字面量自动入池 |
| `intern()` | 手动将字符串放入常量池，返回池中引用 |
| `==` vs `equals` | == 比引用，equals 比内容，**比较字符串始终用 equals** |
| 编译期折叠 | 两个字面量 `+` 编译器直接合并，不走 StringBuilder |
| `+` 底层 | 单次拼接 → StringBuilder；Java 9+ → invokedynamic |
| `StringBuilder` | 可变，非线程安全，单线程拼接首选 |
| `StringBuffer` | 可变，线程安全（synchronized），几乎不用 |
| `hashCode` 缓存 | 第一次计算后存入 hash 字段，后续直接返回 |
| `trim` vs `strip` | trim 去 ASCII 空白，strip 去 Unicode 空白（Java 11+，更推荐） |
| `split` 正则 | 参数是正则，`.` `|` `*` 等需转义；`limit=-1` 保留末尾空串 |
| `getBytes` 编码 | 必须显式传 `StandardCharsets.UTF_8`，不要用无参版 |
