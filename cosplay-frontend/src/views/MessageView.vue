<template>
  <div class="message-page">
    <!-- 左侧：会话列表 -->
    <div class="conversation-list">
      <div class="conv-header">
        <h3>消息</h3>
      </div>
      <el-empty v-if="conversations.length === 0" description="暂无会话" :image-size="60" />
      <div v-for="conv in conversations" :key="conv.userId"
        :class="['conv-item', { active: activeUserId === conv.userId }]"
        @click="openConversation(conv)">
        <el-avatar :size="40" :src="conv.avatar" />
        <div class="conv-info">
          <div class="conv-name">{{ conv.nickname }}</div>
          <div class="conv-last">{{ conv.lastMessage }}</div>
        </div>
        <el-badge v-if="conv.unread > 0" :value="conv.unread" class="conv-badge" />
      </div>
    </div>

    <!-- 右侧：聊天区域 -->
    <div class="chat-area">
      <!-- 未选择会话 -->
      <div v-if="!activeUserId" class="chat-empty">
        <el-empty description="选择一个会话开始聊天" />
      </div>

      <template v-else>
        <!-- 消息列表 -->
        <div class="chat-messages" ref="messagesEl" v-loading="historyLoading">
          <div v-if="hasMoreHistory" class="load-history">
            <el-button text size="small" :loading="historyLoading" @click="loadHistory">
              加载更早消息
            </el-button>
          </div>

          <div v-for="msg in messages" :key="msg.id"
            :class="['msg-row', msg.fromUserId === userStore.userInfo?.id ? 'msg-self' : 'msg-other']">
            <el-avatar v-if="msg.fromUserId !== userStore.userInfo?.id"
              :size="32" :src="activeConv?.avatar" />
            <div class="msg-bubble">{{ msg.content }}</div>
            <el-avatar v-if="msg.fromUserId === userStore.userInfo?.id"
              :size="32" :src="userStore.userInfo?.avatar" />
          </div>
        </div>

        <!-- 输入区 -->
        <div class="chat-input-area">
          <el-input
            v-model="inputText"
            type="textarea"
            :rows="2"
            placeholder="输入消息，Enter 发送，Shift+Enter 换行"
            resize="none"
            @keydown.enter.exact.prevent="sendMessage" />
          <el-button type="primary" :disabled="!inputText.trim()" @click="sendMessage">发送</el-button>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { messageApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { useWebSocket } from '@/composables/useWebSocket'
import type { MessageVO, ConversationVO } from '@/types'

const userStore = useUserStore()
const { connect, onMessage, clearUnread } = useWebSocket()

// ── 会话列表 ─────────────────────────────────────────────
interface ConvItem {
  userId: number
  nickname: string
  avatar: string
  lastMessage: string
  unread: number
}
const conversations = ref<ConvItem[]>([])

async function fetchConversations() {
  try {
    const list = await messageApi.getConversations()
    conversations.value = list.map((c: ConversationVO) => ({
      userId: c.userId,
      nickname: c.nickname,
      avatar: c.avatar,
      lastMessage: c.lastMessage,
      unread: c.unreadCount
    }))
  } catch { /* ignore */ }
}
const activeUserId = ref<number | null>(null)
const activeConv = ref<ConvItem | null>(null)

// ── 聊天消息 ─────────────────────────────────────────────
const messages = ref<MessageVO[]>([])
const historyLoading = ref(false)
const hasMoreHistory = ref(false)
const oldestCursor = ref<number | null>(null)
const messagesEl = ref<HTMLElement>()

const inputText = ref('')

// WebSocket 接收到新消息时追加
onMessage((msg) => {
  if (msg.fromUserId === activeUserId.value) {
    messages.value.push({
      id: Date.now(),
      fromUserId: msg.fromUserId,
      toUserId: userStore.userInfo!.id,
      content: msg.content,
      msgType: 0,
      isRead: true,
      createTime: msg.createTime
    })
    scrollBottom()
  } else {
    const conv = conversations.value.find(c => c.userId === msg.fromUserId)
    if (conv) {
      conv.unread++
      conv.lastMessage = msg.content
    } else {
      // 来自新联系人，刷新会话列表以显示头像/昵称
      fetchConversations()
    }
  }
})

async function openConversation(conv: ConvItem) {
  activeUserId.value = conv.userId
  activeConv.value = conv
  conv.unread = 0
  messages.value = []
  oldestCursor.value = null
  await loadHistory()
  clearUnread()
}

async function loadHistory() {
  if (!activeUserId.value) return
  historyLoading.value = true
  try {
    const data = await messageApi.history({
      targetUserId: activeUserId.value,
      lastId: oldestCursor.value,
      size: 20
    })
    // 消息倒序（最新在下）
    const incoming = [...data.list].reverse()
    messages.value = [...incoming, ...messages.value]
    hasMoreHistory.value = data.hasMore
    if (data.list.length > 0) {
      oldestCursor.value = data.list[data.list.length - 1].id
    }
    if (!oldestCursor.value) scrollBottom()
  } finally {
    historyLoading.value = false
  }
}

async function sendMessage() {
  if (!inputText.value.trim() || !activeUserId.value) return
  const content = inputText.value
  inputText.value = ''
  await messageApi.send({ toUserId: activeUserId.value, content })
  messages.value.push({
    id: Date.now(),
    fromUserId: userStore.userInfo!.id,
    toUserId: activeUserId.value,
    content,
    msgType: 0,
    isRead: true,
    createTime: new Date().toISOString()
  })
  // 更新会话最后消息
  if (activeConv.value) activeConv.value.lastMessage = content
  scrollBottom()
}

function scrollBottom() {
  nextTick(() => {
    if (messagesEl.value) {
      messagesEl.value.scrollTop = messagesEl.value.scrollHeight
    }
  })
}

onMounted(async () => {
  await connect()
  await fetchConversations()
})
</script>

<style scoped>
.message-page {
  display: flex;
  height: calc(100vh - 80px);
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}

/* 左侧会话列表 */
.conversation-list {
  width: 260px;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  flex-shrink: 0;
}

.conv-header { padding: 16px; border-bottom: 1px solid #f0f0f0; }
.conv-header h3 { margin: 0; font-size: 16px; font-weight: 600; }

.conv-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  cursor: pointer;
  border-bottom: 1px solid #f5f5f5;
  position: relative;
  transition: background 0.15s;
}
.conv-item:hover { background: #f5f7fa; }
.conv-item.active { background: #ecf5ff; }
.conv-info { flex: 1; min-width: 0; }
.conv-name { font-size: 14px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conv-last { font-size: 12px; color: #909399; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-top: 2px; }
.conv-badge { position: absolute; right: 12px; top: 12px; }

/* 右侧聊天 */
.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-empty { flex: 1; display: flex; align-items: center; justify-content: center; }

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.load-history { text-align: center; margin-bottom: 8px; }

.msg-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.msg-self { flex-direction: row-reverse; }

.msg-bubble {
  max-width: 60%;
  padding: 8px 12px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
  background: #f4f4f5;
  color: #303133;
}

.msg-self .msg-bubble {
  background: #409eff;
  color: #fff;
}

.chat-input-area {
  border-top: 1px solid #e4e7ed;
  padding: 12px;
  display: flex;
  gap: 8px;
  align-items: flex-end;
}

.chat-input-area .el-textarea { flex: 1; }
</style>
