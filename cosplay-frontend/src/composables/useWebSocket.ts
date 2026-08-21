/**
 * WebSocket composable — 基于 STOMP over SockJS
 *
 * 为什么用 STOMP 而不是原生 WebSocket？
 *   原生 WS 只是一个双向通道，没有消息路由、订阅、心跳等概念。
 *   STOMP 是运行在 WS 之上的协议，定义了 SUBSCRIBE/SEND/MESSAGE 帧，
 *   Spring WebSocket（@EnableWebSocketMessageBroker）原生支持 STOMP。
 *   这样前端只需订阅目的地（如 /user/queue/messages），不需要自己解析路由。
 *
 * 为什么用 SockJS？
 *   浏览器/代理/防火墙可能不支持原生 WS，SockJS 会自动降级到
 *   xhr-streaming → xhr-polling，保证在受限网络环境下也能工作。
 */

import { ref, onUnmounted } from 'vue'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { useUserStore } from '@/stores/user'

// 全局单例：整个应用只维护一个 STOMP 连接，避免重复握手
let stompClient: Client | null = null
let connectPromise: Promise<void> | null = null

// 全局未读消息计数，所有调用该 composable 的组件共享
export const unreadCount = ref(0)

// 消息回调列表，MessageView 订阅后可收到实时消息
type MessageCallback = (msg: { fromUserId: number; content: string; createTime: string }) => void
const messageCallbacks: MessageCallback[] = []

export function useWebSocket() {
  const userStore = useUserStore()

  function connect(): Promise<void> {
    if (stompClient?.connected) return Promise.resolve()
    if (connectPromise) return connectPromise

    connectPromise = new Promise((resolve, reject) => {
      stompClient = new Client({
        // SockJS 工厂：每次需要建立底层连接时调用
        webSocketFactory: () => new SockJS('/ws'),

        // 连接头：把 Sa-Token 的 token 放在 STOMP CONNECT 帧里
        // 后端握手拦截器从这里读取 token 验证身份
        connectHeaders: {
          token: userStore.token
        },

        // 心跳：每 30 秒互发一次，检测连接是否还活着
        heartbeatIncoming: 30000,
        heartbeatOutgoing: 30000,

        onConnect: () => {
          // 订阅服务端推送给当前用户的消息
          // /user/queue/messages 是 Spring 的用户目的地，
          // 只有当前登录用户能收到发给自己的消息
          stompClient!.subscribe('/user/queue/messages', (frame) => {
            try {
              const msg = JSON.parse(frame.body)
              unreadCount.value++
              messageCallbacks.forEach(cb => cb(msg))
            } catch { /* ignore malformed frames */ }
          })

          // 订阅系统通知（订单状态变更等）
          stompClient!.subscribe('/user/queue/notify', (frame) => {
            try {
              const notify = JSON.parse(frame.body)
              unreadCount.value++
              // 可在此触发 ElNotification 弹出系统通知
              import('element-plus').then(({ ElNotification }) => {
                ElNotification({ title: '系统通知', message: notify.content, type: 'info', duration: 4000 })
              })
            } catch { /* ignore */ }
          })

          resolve()
        },

        onStompError: (frame) => {
          console.error('STOMP error', frame)
          connectPromise = null
          reject(new Error(frame.headers.message))
        },

        onDisconnect: () => {
          connectPromise = null
        }
      })

      stompClient.activate()
    })

    return connectPromise
  }

  function disconnect() {
    stompClient?.deactivate()
    stompClient = null
    connectPromise = null
    unreadCount.value = 0
  }

  function sendMessage(toUserId: number, content: string) {
    if (!stompClient?.connected) return
    stompClient.publish({
      destination: '/app/message.send',
      body: JSON.stringify({ toUserId, content })
    })
  }

  function onMessage(cb: MessageCallback) {
    messageCallbacks.push(cb)
    // 组件卸载时自动移除回调
    onUnmounted(() => {
      const idx = messageCallbacks.indexOf(cb)
      if (idx !== -1) messageCallbacks.splice(idx, 1)
    })
  }

  function clearUnread() {
    unreadCount.value = 0
  }

  return { connect, disconnect, sendMessage, onMessage, clearUnread, unreadCount }
}
