# LangChain 系统笔记

---

## 一、LangChain 是什么

LangChain 是构建 LLM 应用的开发框架，核心价值：

- **标准化接口**：用同一套 API 调用 OpenAI、Claude、本地模型等，切换模型无需改业务代码
- **组合能力**：通过 LCEL（LangChain Expression Language）把模型、提示词、工具、输出解析器串联成"链"
- **生态完整**：内置向量数据库集成、Agent框架、RAG工具链、记忆管理等

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

### Token 是什么

大模型通过**分词器（tokenizer）**将文本拆分后的最小语义单元。

- 英文：约 1 个单词 ≈ 1~2 个 token
- 中文：约 1 个汉字 ≈ 1 个 token（具体看模型）
- 费用 = 输入 token 数 × 输入单价 + 输出 token 数 × 输出单价

---

## 三、模型调用方式

### invoke / ainvoke（基础调用）

```python
# 同步调用
response = model.invoke(input, config=None)

# 异步调用（不阻塞主线程，适合 Web 应用）
response = await model.ainvoke(input)
```

`input` 支持三种形式：
1. **字符串**：`model.invoke("你好")`
2. **字典列表**（OpenAI 格式）：`model.invoke([{"role": "user", "content": "你好"}])`
3. **消息对象列表**：`model.invoke([HumanMessage("你好")])`

`config` 以字典形式传入，动态覆盖 init 时的参数：
```python
model.invoke("你好", config={"temperature": 0, "max_tokens": 100})
```

返回值：`AIMessage` 对象，`.content` 取文本内容。

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
# 一次性发送多个请求并发执行
responses = model.batch(["问题1", "问题2", "问题3"])
```

### 输出美化

```python
response.pretty_print()   # 格式化打印消息内容
print(model.model)        # 查看模型名称
```

---

## 四、消息类型

LangChain 中消息通过 `role` 区分：

| 消息类型 | 对应 role | 说明 |
|---------|----------|------|
| `SystemMessage` | system | 设定模型角色和行为规范 |
| `HumanMessage` | user | 用户输入 |
| `AIMessage` | assistant | 模型回复 |
| `ToolMessage` | tool | 工具调用结果，必须包含 `tool_call_id` |

```python
from langchain_core.messages import SystemMessage, HumanMessage, AIMessage, ToolMessage

messages = [
    SystemMessage("你是一个专业的 Python 老师"),
    HumanMessage("什么是列表推导式？"),
]
response = model.invoke(messages)  # 返回 AIMessage
```

### 多轮对话历史管理

每次请求都要把历史消息带上，否则模型失忆：

```python
history = []
history.append(HumanMessage("我叫张三"))
response = model.invoke(history)
history.append(response)  # 把 AIMessage 也加入历史

history.append(HumanMessage("我叫什么名字？"))
response = model.invoke(history)  # 模型能记住"张三"
```

### 消息内容格式

`content` 字段支持多模态（文本 + 图片）：

```python
HumanMessage(content=[
    {"type": "text", "text": "这张图里有什么？"},
    {"type": "image_url", "image_url": {"url": "https://..."}}
])
```

`content_blocks`：消息对象的懒加载字段，被访问后才解析，可格式化输入输出：

```python
response.content_blocks  # 解析为结构化块列表
```

`metadata`：自定义元数据字段，用于消息分组/标记（不是所有模型都支持传给 API）：
```python
HumanMessage("你好", metadata={"session_id": "abc123", "user_id": 1})
```

---

## 五、提示词模板（ChatPromptTemplate）

### 基本用法

模板让提示词可以复用，支持变量填充。

```python
from langchain_core.prompts import ChatPromptTemplate

# 定义模板
prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个{language}专家，请用简洁的方式回答问题"),
    ("human", "{question}"),
])

# 填充变量 → 得到消息列表
messages = prompt.invoke({"language": "Python", "question": "什么是装饰器？"})

# 与模型组合（LCEL 链）
chain = prompt | model
response = chain.invoke({"language": "Python", "question": "什么是装饰器？"})
```

### 三种调用方法

```python
prompt.invoke({"var": "value"})          # 返回 ChatPromptValue（可直接传给模型）
prompt.format({"var": "value"})          # 返回字符串
prompt.format_messages({"var": "value"}) # 返回消息对象列表
```

### 传入消息对象（不使用变量）

```python
# 直接传消息对象时，不能在模板中使用变量
prompt = ChatPromptTemplate.from_messages([
    SystemMessage("你是专家"),
    HumanMessage("{question}"),  # ❌ 这样写变量无效，因为是对象不是元组
])

# ✅ 正确：用元组 (role, "模板字符串") 才支持变量
prompt = ChatPromptTemplate.from_messages([
    ("system", "你是专家"),
    ("human", "{question}"),    # ✅ 元组形式支持变量
])
```

### 部分变量预填充（partial）

```python
# 先填充固定变量，生成新模板
partial_prompt = prompt.partial(language="Python")

# 使用时只需传剩余变量
chain = partial_prompt | model
chain.invoke({"question": "什么是 GIL？"})
```

### 消息占位符（MessagesPlaceholder）

用于在模板中插入动态的消息列表（如对话历史）：

```python
from langchain_core.prompts import MessagesPlaceholder

prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个助手"),
    MessagesPlaceholder("history"),   # 这里插入历史消息列表
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

每个组件都实现了 `Runnable` 接口，支持 invoke / stream / batch / ainvoke 等方法，链整体也支持这些方法。

```python
# 流式输出整个链
for chunk in chain.stream({"question": "讲个故事"}):
    print(chunk, end="", flush=True)

# 批量调用链
results = chain.batch([{"question": "Q1"}, {"question": "Q2"}])
```

---

## 七、LangSmith（可观测性平台）

LangSmith 是 LangChain 官方的追踪平台，记录每次调用的输入/输出/token 消耗/耗时。

```python
import os
os.environ["LANGCHAIN_TRACING_V2"] = "true"
os.environ["LANGCHAIN_API_KEY"] = "ls__..."
os.environ["LANGCHAIN_PROJECT"] = "my-project"   # 项目名称

# 之后的所有 LangChain 调用会自动上报到 LangSmith
```

登录 `smith.langchain.com` 即可查看调用链路图，适合调试复杂 Agent。

---

## 八、工具（Tools）

工具是"明确指定了输入和输出的可调用函数"，让模型可以调用外部能力（搜索、计算、数据库查询等）。

### 定义工具

**方法一：@tool 装饰器（推荐）**

```python
from langchain_core.tools import tool
from pydantic import BaseModel, Field
from typing import Literal

# 简单写法：docstring 自动成为 description
@tool
def get_weather(city: str) -> str:
    """查询指定城市的天气。city: 城市名称（中文）"""
    return f"{city}今天天气晴，气温25度"

# 复杂写法：自定义参数 schema
class SearchInput(BaseModel):
    query: str = Field(description="搜索关键词")
    max_results: int = Field(default=5, description="最大返回条数")
    category: Literal["新闻", "技术", "生活"] = Field(description="搜索分类")

@tool(args_schema=SearchInput)
def search(query: str, max_results: int = 5, category: str = "新闻") -> str:
    """在互联网上搜索信息"""
    return f"搜索 '{query}' 的前{max_results}条{category}结果..."
```

**方法二：不用装饰器**

```python
from langchain_core.tools import StructuredTool

def add(a: int, b: int) -> int:
    """把两个数相加"""
    return a + b

tool = StructuredTool.from_function(add)
```

### 绑定工具到模型

```python
model_with_tools = model.bind_tools([get_weather, search])

response = model_with_tools.invoke("北京今天天气怎么样？")
# response.tool_calls 包含模型决定调用的工具信息
print(response.tool_calls)
# [{"name": "get_weather", "args": {"city": "北京"}, "id": "call_xxx"}]
```

### 完整工具调用流程

```python
from langchain_core.messages import ToolMessage

# 1. 模型决定调用哪些工具
response = model_with_tools.invoke(messages)

# 2. 我们在应用层执行工具
if response.tool_calls:
    for tool_call in response.tool_calls:
        tool_name = tool_call["name"]
        tool_args = tool_call["args"]
        tool_result = tools_map[tool_name].invoke(tool_args)
        
        # 3. 把工具结果封装成 ToolMessage 追加到消息历史
        messages.append(response)  # AIMessage（含 tool_calls）
        messages.append(ToolMessage(
            content=str(tool_result),
            tool_call_id=tool_call["id"]
        ))

# 4. 再次调用模型，得到最终回答
final_response = model_with_tools.invoke(messages)
```

### 工具配置参数

```python
model_with_tools = model.bind_tools(
    tools=[get_weather, search],
    tool_choice="auto",     # auto（模型自己决定）/ required（必须调用）/ none（不调用）
    # 也可以指定工具名：tool_choice={"type": "function", "function": {"name": "get_weather"}}
)
```

### 多工具并行调用

模型可以在一次回复中调用多个工具：

```python
response.tool_calls  # 可能包含多个 tool_call 对象
# 需要对每个都执行并追加 ToolMessage
```

### 实践建议

- 工具 description 要清晰准确，直接影响模型的工具选择
- 工具应功能单一，一个工具只做一件事
- 工具最好返回字符串（方便模型读取）
- 处理工具调用失败，返回错误信息而非抛出异常
- 同步函数和异步函数都可以，对应 invoke 和 ainvoke

---

## 九、结构化输出

让模型按照指定 schema 输出 JSON，而非自由文本。

```python
from pydantic import BaseModel, Field

class MovieReview(BaseModel):
    title: str = Field(description="电影名称")
    rating: float = Field(description="评分，1-10分", ge=1, le=10)
    summary: str = Field(description="简短评价，50字以内")
    recommend: bool = Field(description="是否推荐观看")

# 方式一：with_structured_output（推荐，自动处理解析）
structured_model = model.with_structured_output(MovieReview)
result = structured_model.invoke("评价一下《流浪地球》")
print(result.title)    # 访问结构化字段
print(result.rating)

# 方式二：输出解析器
from langchain_core.output_parsers import JsonOutputParser

parser = JsonOutputParser(pydantic_object=MovieReview)
chain = prompt | model | parser
result = chain.invoke({"movie": "流浪地球"})
```

---

## 十、智能体（Agent）

Agent = 让模型自主决策"调用哪些工具、调用几次、以什么顺序"，直到得到最终答案。

### ReAct Agent（最常用模式）

ReAct = **Re**asoning + **Act**ing：模型交替进行推理（思考做什么）和行动（调用工具）。

```python
from langchain.agents import create_react_agent, AgentExecutor
from langchain import hub

# 使用官方 ReAct 提示词模板
prompt = hub.pull("hwchase17/react")

# 创建 agent
agent = create_react_agent(model, tools=[search, get_weather], prompt=prompt)

# AgentExecutor 处理循环调用
executor = AgentExecutor(agent=agent, tools=[search, get_weather], verbose=True)

result = executor.invoke({"input": "北京今天天气如何？搜索相关新闻"})
print(result["output"])
```

### Tool Calling Agent（更简洁，推荐）

```python
from langchain.agents import create_tool_calling_agent, AgentExecutor

prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个有用的助手"),
    MessagesPlaceholder("chat_history", optional=True),
    ("human", "{input}"),
    MessagesPlaceholder("agent_scratchpad"),  # Agent 的中间步骤
])

agent = create_tool_calling_agent(model, tools, prompt)
executor = AgentExecutor(agent=agent, tools=tools, verbose=True, max_iterations=5)

result = executor.invoke({"input": "帮我查询今天北京的天气"})
```

---

## 十一、对话历史与记忆管理

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

### RunnableWithMessageHistory（自动管理）

```python
from langchain_core.runnables.history import RunnableWithMessageHistory

chain = prompt | model

chain_with_history = RunnableWithMessageHistory(
    chain,
    get_session_history=lambda session_id: InMemoryChatMessageHistory(),
    input_messages_key="question",
    history_messages_key="history",
)

# 通过 configurable session_id 区分不同用户的会话
chain_with_history.invoke(
    {"question": "我叫张三"},
    config={"configurable": {"session_id": "user_001"}}
)
chain_with_history.invoke(
    {"question": "我叫什么名字？"},
    config={"configurable": {"session_id": "user_001"}}  # 同一 session 能记住历史
)
```

---

## 十二、RAG（检索增强生成）

RAG = **R**etrieval **A**ugmented **G**eneration，让模型基于你的私有文档回答问题。

### 核心流程

```
原始文档 → 分块（Chunking）→ 向量化（Embedding）→ 存入向量数据库
                                                           ↓
用户提问 → 向量化 → 相似度检索（召回Top-K相关块）→ 拼入Prompt → 模型生成答案
```

### 代码示例

```python
from langchain_community.document_loaders import TextLoader
from langchain.text_splitter import RecursiveCharacterTextSplitter
from langchain_openai import OpenAIEmbeddings
from langchain_community.vectorstores import Chroma
from langchain.chains import RetrievalQA

# 1. 加载文档
loader = TextLoader("document.txt", encoding="utf-8")
docs = loader.load()

# 2. 文档分块
splitter = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)
chunks = splitter.split_documents(docs)

# 3. 向量化并存入向量数据库
embedding = OpenAIEmbeddings()
vectorstore = Chroma.from_documents(chunks, embedding, persist_directory="./chroma_db")

# 4. 构建检索链
retriever = vectorstore.as_retriever(search_kwargs={"k": 3})  # 召回前3个最相关块
qa_chain = RetrievalQA.from_chain_type(llm=model, retriever=retriever)

# 5. 问答
answer = qa_chain.invoke({"query": "文档里说了什么？"})
print(answer["result"])
```

---

## 十三、MCP（Model Context Protocol）

MCP 是 Anthropic 提出的开放协议，让 AI 应用能以标准化方式连接外部数据源和工具。

**概念类比**：MCP 之于 AI 应用，就像 USB 之于硬件——统一接口，任意设备即插即用。

### MCP vs LangChain Tools 对比

| 对比项 | LangChain Tools | MCP Tools |
|--------|----------------|-----------|
| 实现位置 | 在应用内部定义 | 独立服务（MCP Server）|
| 跨应用复用 | 需要手动复制代码 | 任意 MCP 客户端都能调用 |
| 生态 | LangChain 生态内 | 跨框架、跨语言 |
| 适用场景 | 应用内部工具 | 需要共享的基础能力 |

### 在 LangChain 中使用 MCP 工具

```python
from langchain_mcp_adapters.client import MultiServerMCPClient

# 连接 MCP 服务器
async with MultiServerMCPClient({
    "filesystem": {
        "command": "npx",
        "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"],
        "transport": "stdio"
    }
}) as client:
    tools = client.get_tools()           # 获取 MCP 服务器暴露的工具
    model_with_tools = model.bind_tools(tools)  # 绑定到模型
    response = await model_with_tools.ainvoke("列出当前目录的文件")
```

---

## 十四、关键概念速查

| 概念 | 说明 |
|------|------|
| `ChatModel` | 接受消息列表，返回 AIMessage 的模型接口 |
| `Runnable` | LangChain 所有组件的基类，支持 invoke/stream/batch |
| `LCEL` | 用 `\|` 把 Runnable 串联成链的语法 |
| `ChatPromptTemplate` | 带变量占位符的提示词模板 |
| `MessagesPlaceholder` | 在模板中插入消息列表的占位符 |
| `tool` | 装饰器，把函数变成模型可调用的工具 |
| `with_structured_output` | 让模型按 Pydantic schema 输出结构化数据 |
| `AgentExecutor` | 执行 Agent 循环（模型 → 工具 → 模型 → ...）的运行器 |
| `RAG` | 检索增强生成，让模型基于私有文档回答 |
| `Embedding` | 把文本转换为向量（数字数组），用于相似度检索 |
| `Vector Store` | 存储和检索向量的数据库（Chroma、Pinecone、FAISS等）|
| `MCP` | 标准化工具协议，让 AI 应用跨框架复用工具 |
