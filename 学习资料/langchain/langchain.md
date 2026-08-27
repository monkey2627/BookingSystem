# LangChain 系统笔记

---

## 一、LangChain 是什么

LangChain 是构建 LLM 应用的开发框架，核心价值：

- **标准化接口**：用同一套 API 调用 OpenAI、Claude、本地模型等，切换模型无需改业务代码
- **组合能力**：通过 LCEL（LangChain Expression Language）把模型、提示词、工具、输出解析器串联成"链"
- **生态完整**：内置向量数据库集成、Agent 框架、RAG 工具链、记忆管理等

---

## 二、模型初始化

### 常用初始化参数

```python
from langchain_openai import ChatOpenAI

model = ChatOpenAI(
    model="gpt-4o",              # 模型名称
    temperature=0.7,             # 随机性（0=确定，2=最随机，推荐 0~1）
    max_tokens=2048,             # 最大输出 token 数
    timeout=30,                  # 请求超时秒数
    max_retries=2,               # 失败重试次数
    api_key="sk-...",            # 也可通过环境变量 OPENAI_API_KEY 设置
    base_url="https://...",      # 自定义 API 地址（国内代理、本地部署等）
    model_kwargs={               # 模型支持但 LangChain 未直接暴露的参数
        "top_p": 0.9,
        "frequency_penalty": 0.5,
    },
    extra_body={                 # 模型厂商基于 OpenAI 协议扩展的字段
        "enable_thinking": True, # 如 DeepSeek 的思考模式
    }
)
```

**参数优先级**：`model_kwargs` 存放模型支持但 LangChain 未列出的通用参数；`extra_body` 存放特定厂商扩展的私有字段（两者不同层级）。

### Token 是什么

大模型通过**分词器（tokenizer）**将文本拆分后的最小语义单元：
- 英文：约 1 个单词 ≈ 1~2 个 token
- 中文：约 1 个汉字 ≈ 1 个 token（具体看模型）
- **费用 = 输入 token 数 × 输入单价 + 输出 token 数 × 输出单价**

---

## 三、模型调用方式

### invoke / ainvoke（基础调用）

```python
# 同步调用
response = model.invoke(input, config=None)

# 异步调用（不阻塞主线程，适合 Web 应用）
response = await model.ainvoke(input)
```

**`input` 支持三种形式：**

```python
# 1. 字符串
model.invoke("你好")

# 2. 字典列表（OpenAI 原生格式）
model.invoke([{"role": "user", "content": "你好"}])

# 3. 消息对象列表（LangChain 推荐）
model.invoke([HumanMessage("你好")])
```

**`config` 参数**：以字典形式传入，在运行时动态覆盖 init 时的参数，实现对每个请求的独立控制：

```python
model.invoke("你好", config={"temperature": 0, "max_tokens": 100})
```

**返回值**：`AIMessage` 对象，`.content` 取文本内容，`.pretty_print()` 格式化打印。

### stream（流式输出）

```python
for chunk in model.stream("讲一个故事"):
    print(chunk.content, end="", flush=True)

# 异步流式
async for chunk in model.astream("讲一个故事"):
    print(chunk.content, end="", flush=True)
```

### batch（批量调用）

```python
# 一次性并发发送多个请求
responses = model.batch(["问题1", "问题2", "问题3"])
```

---

## 四、消息类型

LangChain 中所有消息通过 `role` 区分，对应关系：

| 消息类型 | role | 说明 |
|---------|------|------|
| `SystemMessage` | system | 设定模型角色和行为规范 |
| `HumanMessage` | user | 用户输入 |
| `AIMessage` | assistant | 模型回复 |
| `ToolMessage` | tool | 工具调用结果，**必须包含 `tool_call_id`** |

```python
from langchain_core.messages import SystemMessage, HumanMessage, AIMessage, ToolMessage

messages = [
    SystemMessage("你是一个专业的 Python 老师"),
    HumanMessage("什么是列表推导式？"),
]
response = model.invoke(messages)   # 返回 AIMessage
```

### 多轮对话历史管理

模型无状态，每次请求必须把完整历史带上，否则"失忆"：

```python
history = []
history.append(HumanMessage("我叫张三"))
response = model.invoke(history)
history.append(response)           # 把 AIMessage 也加入历史

history.append(HumanMessage("我叫什么名字？"))
response = model.invoke(history)   # 模型能记住"张三"
```

### 消息内容格式详解

**多模态内容**（文本 + 图片）：

```python
HumanMessage(content=[
    {"type": "text", "text": "这张图里有什么？"},
    {"type": "image_url", "image_url": {"url": "https://..."}}
])
```

**`content_blocks`**：消息对象的懒加载字段，被访问后才解析，可统一格式化输入输出：

```python
response.content_blocks   # 访问时才解析，返回结构化块列表
```

**`metadata`**：自定义元数据字段，用于消息分组/标记（不传给模型 API，仅应用层使用）：

```python
HumanMessage("你好", metadata={"session_id": "abc123", "user_id": 1})
```

---

## 五、提示词模板（ChatPromptTemplate）

### 基本用法

模板让提示词可复用，支持变量占位符填充。

```python
from langchain_core.prompts import ChatPromptTemplate

prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个{language}专家，请用简洁的方式回答问题"),
    ("human", "{question}"),
])

# 与模型组合成链（LCEL）
chain = prompt | model
response = chain.invoke({"language": "Python", "question": "什么是装饰器？"})
```

### 三种调用方法对比

```python
prompt.invoke({"var": "value"})          # 返回 ChatPromptValue（可直接传给模型）
prompt.format({"var": "value"})          # 返回字符串
prompt.format_messages({"var": "value"}) # 返回消息对象列表
```

### 元组 vs 消息对象

```python
# ❌ 错误：直接传消息对象，变量占位符无效
prompt = ChatPromptTemplate.from_messages([
    SystemMessage("你是专家"),
    HumanMessage("{question}"),   # 这里的 {question} 不会被替换，是字面字符串
])

# ✅ 正确：用元组 (role, "模板字符串") 才支持变量替换
prompt = ChatPromptTemplate.from_messages([
    ("system", "你是专家"),
    ("human", "{question}"),      # 这里的 {question} 会被替换
])
```

### 部分变量预填充（partial）

```python
# 先固定部分变量，生成新模板
partial_prompt = prompt.partial(language="Python")

# 使用时只传剩余变量
chain = partial_prompt | model
chain.invoke({"question": "什么是 GIL？"})
```

### 消息占位符（MessagesPlaceholder）

用于在模板固定位置插入一个动态消息列表（如对话历史）：

```python
from langchain_core.prompts import MessagesPlaceholder

prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个助手"),
    MessagesPlaceholder("history"),    # 运行时插入历史消息列表
    ("human", "{question}"),
])

chain = prompt | model
chain.invoke({
    "history": [
        HumanMessage("我叫张三"),
        AIMessage("你好，张三！"),
    ],
    "question": "我叫什么名字？"
})
```

---

## 六、LCEL（LangChain Expression Language）

用 `|` 管道符把组件串联，形成"链"：

```python
chain = prompt | model | output_parser
result = chain.invoke({"question": "..."})
```

**核心机制**：每个组件都实现了 `Runnable` 接口，链整体也是一个 Runnable，支持所有调用方式：

```python
# 流式输出整个链
for chunk in chain.stream({"question": "讲个故事"}):
    print(chunk, end="", flush=True)

# 批量调用链
results = chain.batch([{"question": "Q1"}, {"question": "Q2"}])

# 异步调用链
result = await chain.ainvoke({"question": "..."})
```

**Runnable 接口方法汇总**：

| 方法 | 说明 |
|------|------|
| `invoke` | 同步调用，返回完整结果 |
| `ainvoke` | 异步调用 |
| `stream` | 同步流式输出 |
| `astream` | 异步流式输出 |
| `batch` | 批量并发调用 |
| `abatch` | 异步批量调用 |

---

## 七、LangSmith（可观测性平台）

LangSmith 是 LangChain 官方的追踪平台，记录每次调用的输入/输出/token 消耗/耗时，适合调试复杂链路。

```python
import os
os.environ["LANGCHAIN_TRACING_V2"] = "true"
os.environ["LANGCHAIN_API_KEY"] = "ls__..."
os.environ["LANGCHAIN_PROJECT"] = "my-project"

# 之后所有 LangChain 调用自动上报，无需改业务代码
```

登录 `smith.langchain.com` 查看每一步的输入输出、耗时、token 花费，以及完整的调用链路图。

---

## 八、工具（Tools）

工具是"明确指定了输入和输出的可调用函数"，让模型可以调用外部能力（搜索、计算、数据库查询等）。

### 定义工具

**方法一：@tool 装饰器（推荐）**

```python
from langchain_core.tools import tool
from pydantic import BaseModel, Field
from typing import Literal

# 简单写法：docstring 自动成为 description，影响模型如何选择工具
@tool
def get_weather(city: str) -> str:
    """查询指定城市的天气。city: 城市名称（中文）"""
    return f"{city}今天天气晴，气温25度"

# 复杂写法：自定义参数 schema（精确控制参数描述和约束）
class SearchInput(BaseModel):
    query: str = Field(description="搜索关键词")
    max_results: int = Field(default=5, description="最大返回条数")
    category: Literal["新闻", "技术", "生活"] = Field(description="搜索分类")
    # Literal 限制参数只能从固定值中取，防止模型乱填

@tool(args_schema=SearchInput)
def search(query: str, max_results: int = 5, category: str = "新闻") -> str:
    """在互联网上搜索信息"""
    return f"搜索 '{query}' 的前{max_results}条{category}结果..."
```

**方法二：StructuredTool（不用装饰器）**

```python
from langchain_core.tools import StructuredTool

def add(a: int, b: int) -> int:
    """把两个数相加"""
    return a + b

tool = StructuredTool.from_function(add)
```

### 绑定工具到模型

```python
model_with_tools = model.bind_tools(
    tools=[get_weather, search],
    tool_choice="auto",     # auto（模型自己决定）/ required（必须调用）/ none（不调用）
    # 强制指定某个工具：tool_choice={"type": "function", "function": {"name": "get_weather"}}
)

response = model_with_tools.invoke("北京今天天气怎么样？")
print(response.tool_calls)
# [{"name": "get_weather", "args": {"city": "北京"}, "id": "call_xxx"}]
```

**关键理解**：模型返回的 `tool_calls` 只是"我想调用哪个工具、传什么参数"的意图声明，**实际执行工具是应用层的责任**，不是模型自动执行的。

### 完整工具调用流程

```python
from langchain_core.messages import ToolMessage

messages = [HumanMessage("北京今天天气如何？")]

# 第一轮：模型决定调用哪些工具
response = model_with_tools.invoke(messages)
messages.append(response)   # 把含 tool_calls 的 AIMessage 加入历史

# 应用层执行工具（可能一次返回多个 tool_call）
if response.tool_calls:
    for tool_call in response.tool_calls:
        tool_result = tools_map[tool_call["name"]].invoke(tool_call["args"])
        messages.append(ToolMessage(
            content=str(tool_result),
            tool_call_id=tool_call["id"]   # 必须对应 tool_calls 里的 id
        ))

# 第二轮：模型看到工具结果，生成最终回答
final_response = model_with_tools.invoke(messages)
```

**多工具并行调用**：模型可在一次回复中的 `tool_calls` 里返回多个工具调用意图，需要对每个都执行并追加 ToolMessage。

### 工具实践建议

- description 要清晰准确，它直接决定模型选择哪个工具
- 一个工具只做一件事（单一职责）
- 工具最好返回字符串，方便模型读取
- 调用失败时返回错误描述字符串，而非抛出异常（让模型感知到失败并处理）
- 同步/异步函数均可（对应 invoke/ainvoke）

---

## 九、结构化输出

让模型按照指定 schema 输出结构化 JSON，而非自由文本。

### 四种 Schema 定义方式

```python
# 方式一：Pydantic（推荐，有类型验证）
from pydantic import BaseModel, Field

class MovieReview(BaseModel):
    """电影评价"""
    title: str = Field(description="电影名称")
    rating: float = Field(description="评分1-10", ge=1, le=10)
    summary: str = Field(description="简短评价，50字以内")
    recommend: bool = Field(description="是否推荐观看")

# 方式二：TypedDict（简单，无验证）
from typing import TypedDict
class MovieReview(TypedDict):
    title: str
    rating: float

# 方式三：JSON Schema（字典形式）
schema = {
    "type": "object",
    "properties": {
        "title": {"type": "string"},
        "rating": {"type": "number"}
    }
}

# 方式四：dataclass
from dataclasses import dataclass
@dataclass
class MovieReview:
    title: str
    rating: float
```

### 两种使用方式

```python
# 推荐：with_structured_output（自动处理解析，一步到位）
structured_model = model.with_structured_output(MovieReview)
result = structured_model.invoke("评价一下《流浪地球》")
print(result.title, result.rating)  # 直接访问字段

# 输出解析器（链式写法）
from langchain_core.output_parsers import JsonOutputParser
parser = JsonOutputParser(pydantic_object=MovieReview)
chain = prompt | model | parser
result = chain.invoke({"movie": "流浪地球"})
```

---

## 十、智能体（Agent）

Agent = 让模型**自主决策**：调用哪些工具、调用几次、以什么顺序，循环直到得到最终答案。

### 核心概念：ReAct 模式

ReAct = **Re**asoning + **Act**ing，模型交替进行：
- **Reasoning（推理）**：思考下一步要做什么
- **Acting（行动）**：调用工具获取信息
- **Observation（观察）**：看工具返回结果，决定是否继续

### 创建 Agent（LangGraph prebuilt）

```python
from langgraph.prebuilt import create_react_agent

agent = create_react_agent(
    model,                         # 模型实例，或直接传模型名称字符串
    tools=[search, get_weather],   # 工具列表（静态绑定）
    name="my_assistant",           # Agent 名称
    state_modifier="你是一个有用的助手，请尽量简洁回答"  # 系统提示词
)
```

**调用方式**（输入输出与 model.invoke 一致，都是消息列表）：

```python
result = agent.invoke({"messages": [HumanMessage("北京今天天气如何？")]})
print(result["messages"][-1].content)   # 最终回答
```

### 流式输出与 stream_mode

```python
for chunk in agent.stream(
    {"messages": [HumanMessage("帮我查天气并搜索相关新闻")]},
    stream_mode="updates"   # 控制输出粒度
):
    print(chunk)
```

| stream_mode | 说明 | 适用场景 |
|-------------|------|---------|
| `updates`（默认） | 只输出每步**新增/变化**的内容 | 通用，节省输出量 |
| `values` | 每步都输出**完整状态** | 需要全局状态快照 |
| `messages` | 流式输出**每个 token** + 元数据 | 实时打字机效果 |
| `tasks` | 输出每个任务的开始/结束时间 | 性能分析 |
| `debug` | 详细调试信息 | 开发调试 |
| `custom` | 自定义输出 | 特殊场景 |

### Agent 结构化输出

Agent 最终回答也可以是结构化数据：

**ProviderStrategy（原生结构化，推荐）**：模型原生支持时直接用

```python
agent = create_react_agent(
    model,
    tools=tools,
    response_format=MovieReview   # 直接传 Pydantic 模型
)
```

**ToolStrategy（虚拟工具策略）**：模型不支持原生结构化输出时使用，通过创建一个"虚拟工具"引导模型输出符合 schema 的内容

```python
from langgraph.prebuilt import create_react_agent
from langgraph.prebuilt.chat_agent_executor import StructuredResponseSchema

agent = create_react_agent(
    model,
    tools=tools,
    response_format=(
        "请用指定格式总结结果",
        MovieReview    # 或 Union(TypeA, TypeB) 提供多个 schema 让模型选择
    )
)
```

ToolStrategy 的 `handle_errors` 参数控制解析失败时的行为：

| 值 | 行为 |
|----|------|
| `True`（默认） | 自动捕获所有异常，触发重试 |
| `False` | 关闭自动重试，直接抛出异常 |
| 异常类型 | 只捕获该类异常（如 `StructuredOutputValidationError`） |
| 错误处理函数 | 调用自定义函数处理错误 |
| 字符串 | 把该字符串作为错误信息返回给模型 |

---

## 十一、中间件（Hooks）

中间件是**钩子函数**（hook）——在 Agent 执行流程的特定节点被框架自动调用的扩展函数，用于处理不属于核心业务逻辑但影响执行过程的问题（如限流、鉴权、日志、容错等）。

### 内置中间件分类

| 类别 | 作用 | 典型场景 |
|------|------|---------|
| **成本与资源控制** | 限制模型调用次数、token 预算 | 防止费用超支，限制单次对话最多调用 N 次模型 |
| **稳定性与容错** | 自动重试、降级回退 | 模型超时自动重试，失败时换备用模型 |
| **安全与合规** | 输入/输出内容过滤 | 拦截敏感词，防止提示词注入 |
| **决策增强** | 路由、规划增强 | 根据问题类型自动选择不同子 Agent |
| **执行能力扩展** | 并行/串行任务编排 | 多个工具并发执行，汇总结果 |
| **开发调试** | 追踪、测试辅助 | 记录每步输入输出，方便调试 |

### 多中间件的执行顺序

**书写顺序决定执行顺序**，与洋葱模型类似：

```
请求进入 → 中间件A（前处理）→ 中间件B（前处理）→ 核心逻辑 → 中间件B（后处理）→ 中间件A（后处理）→ 响应返回
```

### 自定义中间件（基于装饰器）

钩子函数接收两个参数：`state`（当前状态）和 `config`（运行时配置），由框架自动传入：

```python
from langgraph.prebuilt import create_react_agent
from langchain_core.runnables import RunnableConfig

# before_model_hook：模型调用前触发
def my_before_hook(state, config: RunnableConfig):
    messages = state["messages"]
    print(f"[Hook] 即将调用模型，当前消息数：{len(messages)}")
    # 可以修改 state 或直接返回（不修改则返回 None）
    return state

# after_model_hook：模型调用后触发
def my_after_hook(state, config: RunnableConfig):
    last_message = state["messages"][-1]
    print(f"[Hook] 模型回复：{last_message.content[:50]}...")
    return state

agent = create_react_agent(
    model,
    tools=tools,
    pre_model_hook=my_before_hook,
    post_model_hook=my_after_hook,
)
```

**实际应用示例——调用次数限制**：

```python
def call_limit_hook(state, config: RunnableConfig):
    call_count = state.get("call_count", 0)
    if call_count >= 5:
        raise Exception("已达到最大调用次数限制")
    state["call_count"] = call_count + 1
    return state
```

---

## 十二、对话历史与记忆管理

### 手动管理（最可控）

```python
from langchain_core.chat_history import InMemoryChatMessageHistory

history = InMemoryChatMessageHistory()

def chat(user_input: str) -> str:
    history.add_user_message(user_input)
    response = model.invoke(history.messages)
    history.add_ai_message(response.content)
    return response.content
```

### RunnableWithMessageHistory（自动管理，多用户 Session）

```python
from langchain_core.runnables.history import RunnableWithMessageHistory

chain = prompt | model

chain_with_history = RunnableWithMessageHistory(
    chain,
    get_session_history=lambda session_id: InMemoryChatMessageHistory(),
    input_messages_key="question",
    history_messages_key="history",
)

# session_id 区分不同用户的对话（相同 id = 同一对话，共享历史）
chain_with_history.invoke(
    {"question": "我叫张三"},
    config={"configurable": {"session_id": "user_001"}}
)
chain_with_history.invoke(
    {"question": "我叫什么名字？"},
    config={"configurable": {"session_id": "user_001"}}   # 同一 session，能记住"张三"
)
```

### 记忆管理策略

| 策略 | 做法 | 适用场景 |
|------|------|---------|
| 全量历史 | 每次带上所有消息 | 短对话，上下文不超出 token 限制 |
| 滑动窗口 | 只保留最近 N 轮 | 长对话，控制 token 消耗 |
| 摘要记忆 | 用模型把历史摘要成一段话，替换旧历史 | 超长对话，保留关键信息 |
| 向量记忆 | 历史向量化存入向量库，按需检索相关历史 | 需要跨轮次语义检索 |

---

## 十三、RAG（检索增强生成）

RAG = **R**etrieval **A**ugmented **G**eneration，让模型基于私有文档回答问题，解决"模型不知道你的私有数据"的问题。

### 核心思路

```
【离线构建阶段】
原始文档 → 分块（Chunking）→ 向量化（Embedding）→ 存入向量数据库

【在线查询阶段】
用户提问 → 向量化 → 相似度检索（召回 Top-K 相关块）→ 拼入 Prompt → 模型生成答案
```

### 为什么要分块？

文档太长无法直接塞入 Prompt（token 限制），分块后只取最相关的块，精准且节省 token。

**分块策略**：
- `chunk_size`：每块的最大字符数（如 500）
- `chunk_overlap`：相邻块重叠字符数（如 50），防止关键信息被切断

### 代码示例

```python
from langchain_community.document_loaders import TextLoader
from langchain.text_splitter import RecursiveCharacterTextSplitter
from langchain_openai import OpenAIEmbeddings
from langchain_community.vectorstores import Chroma

# 1. 加载文档
loader = TextLoader("document.txt", encoding="utf-8")
docs = loader.load()

# 2. 分块
splitter = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)
chunks = splitter.split_documents(docs)

# 3. 向量化并存入向量数据库（Embedding 把文本变成数字数组，相似文本的向量距离近）
embedding = OpenAIEmbeddings()
vectorstore = Chroma.from_documents(chunks, embedding, persist_directory="./chroma_db")

# 4. 构建检索器
retriever = vectorstore.as_retriever(search_kwargs={"k": 3})   # 召回最相关的前3块

# 5. 手动 RAG 链
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.runnables import RunnablePassthrough

prompt = ChatPromptTemplate.from_messages([
    ("system", "根据以下文档回答问题：\n\n{context}"),
    ("human", "{question}")
])

chain = (
    {"context": retriever, "question": RunnablePassthrough()}
    | prompt
    | model
)
answer = chain.invoke("文档里说了什么？")
```

### Embedding 是什么

把文本转换成**高维数字向量**的技术。语义相近的文本，向量在空间中的距离也近（余弦相似度高），这是相似度检索的数学基础。

---

## 十四、MCP（Model Context Protocol）

MCP 是 Anthropic 提出的开放协议，让 AI 应用以**标准化方式**连接外部数据源和工具。

**类比**：MCP 之于 AI 应用 = USB 之于硬件——统一接口，任意设备即插即用。

### MCP vs LangChain Tools

| 对比项 | LangChain Tools | MCP Tools |
|--------|----------------|-----------|
| 实现位置 | 在应用内部代码定义 | 独立进程（MCP Server） |
| 跨应用复用 | 需要复制代码 | 任意 MCP 客户端都能调用 |
| 生态范围 | LangChain 生态内 | 跨框架、跨语言 |
| 适用场景 | 业务专属工具 | 基础能力共享（文件系统、数据库等）|

### 在 LangChain 中使用 MCP

```python
from langchain_mcp_adapters.client import MultiServerMCPClient

async with MultiServerMCPClient({
    "filesystem": {
        "command": "npx",
        "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"],
        "transport": "stdio"
    },
    "web_search": {
        "command": "python",
        "args": ["-m", "mcp_web_search"],
        "transport": "stdio"
    }
}) as client:
    tools = client.get_tools()                      # 获取所有 MCP Server 暴露的工具
    agent = create_react_agent(model, tools=tools)  # 绑定到 Agent
    result = await agent.ainvoke({"messages": [HumanMessage("列出当前目录的文件")]})
```

---

## 十五、关键概念速查

| 概念 | 一句话说明 |
|------|-----------|
| `ChatModel` | 接受消息列表，返回 AIMessage 的模型接口 |
| `Runnable` | LangChain 所有组件的基类，支持 invoke/stream/batch/ainvoke |
| `LCEL` | 用 `\|` 把 Runnable 串联成链的语法 |
| `ChatPromptTemplate` | 带变量占位符的提示词模板，from_messages 创建 |
| `MessagesPlaceholder` | 在模板中插入动态消息列表的占位符（用于历史） |
| `@tool` | 装饰器，把普通函数变成模型可调用的工具 |
| `tool_calls` | AIMessage 的字段，模型表达"想调用哪个工具"的意图（不是真执行）|
| `ToolMessage` | 工具执行结果，必须包含 tool_call_id 与 tool_calls 对应 |
| `with_structured_output` | 让模型按 Pydantic schema 返回结构化对象 |
| `create_react_agent` | LangGraph 创建 ReAct Agent 的工厂函数 |
| `stream_mode` | 控制 Agent 流式输出的粒度（updates/values/messages 等）|
| `pre_model_hook` | Agent 调用模型前触发的钩子函数（中间件）|
| `RAG` | 检索增强生成，给模型挂载私有文档知识库 |
| `Embedding` | 把文本转成数字向量，语义相近则向量相近 |
| `Vector Store` | 存储和检索向量的数据库（Chroma、FAISS、Pinecone 等）|
| `MCP` | 标准化工具协议，让工具跨框架、跨语言复用 |
| `LangSmith` | LangChain 官方调用追踪平台，记录每次调用的 IO 和 token |
