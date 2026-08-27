# 常见模型初始化参数
1. temperature：控制模型输出的随机化，值越大越随机
2. 客户端与连接：
   3. model_kwargs存放模型支持但是langchain没有直接列出来的字段
   4.extra_body:存放模型厂商基于openai api协议扩展的字段
4. 模型推理参数：
   temperature：控制模型输出的随机化，值越大越随机
4. 
# token是什么？
大模型通过分词器将文本拆分后的最小语义单元
# 模型的调用
* invoke、ainvoke
> responce = model.invoke(input,config)

其中config参数以dict形式存在，在调用模型的时候动态配置和控制模型的行为
![img_2.png](img_2.png)，其和init模型的时候传入的那个一样，但如果在运行时显示传入，其会覆盖init中的内容
做到能对每个请求进行不同的配置
input可以直接是：1.直接输入文本2.列表，每个列表元素是一个字典
[{"role:"user","content":"具体的内容"},{}]
多轮对话场景如果不传递历史，ai会失忆
消息对象列表：SystemMessage对象、HumanMessage对象AssistantMessage对象
直接给一个存了对象的列表
返回值：AIMessage的对象
![img.png](img.png)![img_1.png](img_1.png)
* stream
流式输出
* batch
一次性发送多个请求
* 异步方法：不用阻塞主线程，阻塞等待模型返回，加快效率
* 美化模型输出：pretty print
* model.profile有的可能可以查看模型配置
# 消息和提示词模板
## 消息的类型
langchain中消息类型通过role区分，也就是我们传给模型的那个信息里的role
system，为模型设定角色，user：表示用户的输入，assistant：表示大模型的回复，tool：工具调用消息
## 消息格式
* json
* 对象
  * 对象字段：content：即输入给模型的内容![img_4.png](img_4.png)
    * content还可以保存多模态的数据，每一块标明text和type，使用字典类型
    * content_blocks:是一个字典的列表，可以替代原来的contnt字段对输入进行格式化，
    * 同时也可以对我们的输出进行格式化，统一输出格式
    * 直接就是respond.content_blocks,其是懒加载的，被调用后才会被解析
  *       metadata：元数据字段，自己随便定义，对消息进行分组，但并不是所有模型/模型供应商都支持
  * toolMessage：有一个tool_call_id
# 实战
## 对话历史管理
每次将回复append到输入中
# langsmith的基本使用
![img_3.png](img_3.png)
## tracing
# 提示词模板
ChatPromptTemplate
## 实例化
c = ChatPromptTemplate.from_messages([(),(),()])实例化一个模板出来
c.invoke() 
c.format() 返回的是字符串
c.format_messages() 返回的是消息列表
以上三种是根据模板来填充其中字段，得到最终输入给图形的东西
注意消息对象的方式，就不能在模板中声明变量
* BaseMessagePromptTemplate参数列表![img_5.png](img_5.png)
* BaseChatPromptTemplate参数列表：意思就是ChatPromptTemplate.from_messages
* 传入的参数列表中的单个元素仍然可以是一个ChatPromptTemplate类型的
* ![img_6.png](img_6.png)总的例子
* partial()部分变量预填充，可以先填充模板的部分字段，用法：
> c.partial(name="")
* 消息占位符
  * placeholder![img_7.png](img_7.png)
  * messagePlaceholder ![img_8.png](img_8.png)
  * 实际开发中会单独维护一个提示词模板文件，这个文件被人引用比如：
# 工具
* 明确指定了输入和输出的可调用函数
* name.invoke({})
* @tool标注
* model.bind_tools([工具名])
## 调用整体流程
![img_9.png](img_9.png)
注意，工具是我们的agent来调用，请求来自大模型，我们在应用中调用，调用完把结果append
在message（toolmessage类型）里再传给模型
## 工具的定义
### 不使用@tool
声明函数，将函数绑定在模型上
#### convert_to_openai_tool
#### description说明,python本来就有的
说明
Args:
Returns:
* 参数类型的说明
### 使用@tool
* 标识当前函数是一个工具
* @tool(description="")
* @tool(parse_docstring=)
* 自定义args_schema:继承BaseModel
  * Field(description=,default=)标注一个字段
  * Literal，限制参数自能从固定的一部分值里面取
  * 第二种方式，用json schema，直接传一个json的数据
* 多工具调用：大模型的response中tool_calls字段不止一个
    * 案例：
* tool_choice:配置是否强制大模型调用工具，auto，required，none，具体的方法
* 实践经验：清晰的描述，功能单一，处理工具失败，最好直接返回字符串，同步和异步
### 有很多内置的工具，可以直接用
# 结构化输出
## Pydantic结构化
class a(BaseModel):
    """
      工具的描述
    """
## TypedDisct
## JSON Schema
## 数据类
# 智能体
* 统一为create_agent(),将大模型、工具、prompt等传入，来创建一个智能体
* 模型的传入方式：1.传入一个字符串，类似之前模型的创建，注意要加load_dovenv2.直接先创建一个模型，然后传入模型实例
* 调用：agent.invoke(),传入的是消息列表，返回的也是字典包含的消息列表，和模型调用好像是一样的
例子：
* 绑定工具：静态 or 借助中间件动态
* 1.静态：创建的时候传入tools=[ , , ,]
## 工具调用流程分析
ReAct结构 思考-行动-观察
* 设置agent名字：传入name=
## 结构化输出
1.ProviderStrategy原生的结构化输出
2.ToolStrategy不支持原生结构化输出的模型，相当于创建一个虚拟工具，这个工具引导模型产生对应结构的输出
### ToolStrategy详解
三个参数:schema：对应之前讲的四种结构化输出，表明用哪一种；Union(),提供多个结构化类，对应不同的问题可以选用
tool_message_content:设置显示在ToolMessage中content上的数据，因为实际的大模型使用的数据其实不依靠contnt，可以节省token
handle_errors：默认为true，捕获所有异常，false：关闭自动重试，直接抛出异常
设定为指定异常类型：捕获这种异常，有：multiplestructuredoutputserror，structuredoutputvalidationerror
设定为指定的错误处理函数：直接把函数给handle_errors参数
设定为自定义字符串：
## 流式输出及模式
agent.stream()调用，stream_mode参数控制
values：每步都会输出完整的状态信息等
updates：默认使用，只增量更新状态中发生变化的内容
messages：会输出流式返回的token已经相关的元数据
tasks：输出当前任务开始时间，结束时间等
debug:
custom:
# 中间件
# 上下文与记忆
# RAG
# MCP与skills