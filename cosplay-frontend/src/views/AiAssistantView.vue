<template>
  <div class="ai-page">
    <div class="ai-header">
      <div class="ai-title">
        <span class="robot-icon">🤖</span>
        <span>AI 商家推荐助手</span>
      </div>
      <el-button size="small" plain @click="clearHistory" :disabled="sending">清除对话</el-button>
    </div>

    <div class="chat-body" ref="chatBodyRef">
      <!-- 欢迎语 -->
      <div v-if="messages.length === 0" class="welcome">
        <p>你好！我可以帮你根据需求找到合适的妆娘、摄影师或毛娘。</p>
        <p>试试告诉我：你在哪个城市、需要什么服务、大概什么时间？</p>
      </div>

      <!--
        AI 空占位气泡（content='' && streaming=true）整行跳过渲染，
        避免出现孤立的 🤖 头像与 thinking 动画并排两个机器人图标
      -->
      <div
        v-for="msg in messages"
        v-show="msg.role !== 'ai' || msg.content"
        :key="msg.id"
        :class="['msg-row', msg.role === 'user' ? 'user-row' : 'ai-row']"
      >
        <div class="avatar" v-if="msg.role === 'ai'">🤖</div>
        <div :class="['bubble', msg.role]">
          <span class="bubble-text">{{ msg.content }}</span>
          <span v-if="msg.streaming" class="cursor">▌</span>
        </div>
      </div>

      <!-- 思考中占位（工具调用阶段，尚无文字输出时）-->
      <div v-if="thinking" class="msg-row ai-row">
        <div class="avatar">🤖</div>
        <div class="bubble ai thinking-bubble">
          <span class="dot"></span><span class="dot"></span><span class="dot"></span>
        </div>
      </div>
    </div>

    <div class="chat-input-area">
      <el-input
        v-model="inputText"
        type="textarea"
        :rows="2"
        resize="none"
        placeholder="输入你的需求，例如：帮我找北京的妆娘，8月底有档期的…（Enter 发送，Shift+Enter 换行）"
        :disabled="sending"
        @keydown="handleKeydown"
      />
      <el-button
        type="primary"
        :loading="sending"
        :disabled="!inputText.trim()"
        @click="sendMessage"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiApi } from '@/api/index'

interface ChatMessage {
  id: number
  role: 'user' | 'ai'
  content: string
  streaming?: boolean
}

const chatBodyRef = ref<HTMLElement>()
const inputText = ref('')
const messages = ref<ChatMessage[]>([])
const sending = ref(false)
const thinking = ref(false)
let msgIdCounter = 0

function scrollToBottom() {
  nextTick(() => {
    if (chatBodyRef.value) {
      chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
    }
  })
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    if (!sending.value && inputText.value.trim()) {
      sendMessage()
    }
  }
}

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || sending.value) return

  inputText.value = ''
  sending.value = true
  thinking.value = true

  // 添加用户消息气泡
  messages.value.push({ id: ++msgIdCounter, role: 'user', content: text })
  scrollToBottom()

  // 立即推入 AI 占位气泡（content 为空时模板隐藏它，thinking 动画显示）
  // 必须先 push 再通过 messages.value[idx] 访问，确保 Vue 响应式代理能追踪后续 content 变更
  messages.value.push({ id: ++msgIdCounter, role: 'ai', content: '', streaming: true })
  const aiMsgIdx = messages.value.length - 1

  try {
    const response = await aiApi.chat(text)

    if (!response.ok) {
      if (response.status === 401) {
        ElMessage.error('登录已过期，请重新登录')
        messages.value[aiMsgIdx].content = '（登录已过期，请重新登录）'
        messages.value[aiMsgIdx].streaming = false
        return
      }
      throw new Error(`请求失败：${response.status}`)
    }

    const reader = response.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''

      for (const line of lines) {
        if (!line.startsWith('data: ')) continue
        const jsonStr = line.slice(6).trim()
        if (!jsonStr) continue

        let event: { type: string; content?: string }
        try {
          event = JSON.parse(jsonStr)
        } catch {
          continue
        }

        if (event.type === 'token' && event.content) {
          thinking.value = false
          // 通过响应式数组索引访问，保证 Vue 追踪到属性变更
          messages.value[aiMsgIdx].content += event.content
          scrollToBottom()
        } else if (event.type === 'done') {
          messages.value[aiMsgIdx].streaming = false
        } else if (event.type === 'error') {
          thinking.value = false
          messages.value[aiMsgIdx].content = `（出错了：${event.content}）`
          messages.value[aiMsgIdx].streaming = false
        }
      }
    }
  } catch (err) {
    thinking.value = false
    messages.value[aiMsgIdx].content = '（网络异常，请稍后重试）'
    messages.value[aiMsgIdx].streaming = false
    ElMessage.error('请求失败，请检查网络连接')
  } finally {
    thinking.value = false
    sending.value = false
    scrollToBottom()
  }
}

async function clearHistory() {
  try {
    await ElMessageBox.confirm('确定要清除全部对话记录吗？', '提示', {
      confirmButtonText: '清除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await aiApi.clearHistory()
    messages.value = []
    ElMessage.success('对话记录已清除')
  } catch {
    // 用户点取消
  }
}
</script>

<style scoped>
.ai-page {
  max-width: 800px;
  margin: 0 auto;
  height: calc(100vh - 60px);
  display: flex;
  flex-direction: column;
  padding: 16px;
  box-sizing: border-box;
  gap: 12px;
}

.ai-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #fff;
  border-radius: 8px;
  border: 1px solid #e4e7ed;
}

.ai-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.robot-icon {
  font-size: 20px;
}

.chat-body {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px 0;
}

.welcome {
  text-align: center;
  color: #909399;
  font-size: 14px;
  margin-top: 40px;
  line-height: 2;
}

.msg-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}

.user-row {
  flex-direction: row-reverse;
}

.avatar {
  font-size: 24px;
  flex-shrink: 0;
  line-height: 1;
  margin-bottom: 4px;
}

.bubble {
  max-width: 70%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
}

.bubble.user {
  background: #409eff;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.bubble.ai {
  background: #f5f7fa;
  color: #303133;
  border-bottom-left-radius: 4px;
}

.cursor {
  display: inline-block;
  color: #409eff;
  animation: blink 0.8s step-end infinite;
}

@keyframes blink {
  50% { opacity: 0; }
}

.thinking-bubble {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 12px 16px;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #909399;
  animation: bounce 1.2s infinite;
}

.dot:nth-child(2) { animation-delay: 0.2s; }
.dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes bounce {
  0%, 80%, 100% { transform: translateY(0); }
  40% { transform: translateY(-6px); }
}

.chat-input-area {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}

.chat-input-area .el-input {
  flex: 1;
}

.chat-input-area .el-button {
  height: 58px;
  padding: 0 20px;
}
</style>
