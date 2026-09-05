import axios from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { Result } from '@/types'

// 扩展 Axios 请求配置类型，支持 silentError 选项和内部 _retry 标记
// silentError=true：业务错误不弹 toast，由调用方自己处理（适用于"预期内可能失败"的初始化请求）
declare module 'axios' {
  interface AxiosRequestConfig {
    silentError?: boolean
    _retry?: boolean
  }
}

// 为什么要封装 Axios 而不是直接用？
//   每个接口都需要带 token header、处理 401 跳登录、解包 {code, message, data} 结构。
//   如果在每个 API 调用处重复这些逻辑，一旦 token header 名字改了就要改几十个地方。
//   封装后所有接口调用只需关心业务参数，拦截器统一处理通用逻辑。

const request = axios.create({
  baseURL: '/api',    // 配合 vite.config.ts 的 proxy，/api 开头的请求会被代理到后端
  timeout: 10000      // 超时 10 秒，防止接口挂起导致页面一直 loading
})

// 并发刷新控制：多个请求同时 401 时，只触发一次 refresh，其余请求排队等待新 token
let isRefreshing = false
let pendingRequests: Array<(newToken: string) => void> = []

function clearAuthAndRedirect() {
  localStorage.removeItem('token')
  localStorage.removeItem('refreshToken')
  localStorage.removeItem('userInfo')
  ElMessage.error('登录已过期，请重新登录')
  window.location.href = '/login'
}

// ── 请求拦截器：每个请求发出前自动加 token ──────────────────
request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 从 localStorage 读 token（Pinia store 里也会持久化到这里）
    // 不从 Pinia 直接读是为了避免循环依赖（store 会 import request，request 不应再 import store）
    const token = localStorage.getItem('token')
    if (token) {
      config.headers['token'] = token
    }
    return config
  },
  error => Promise.reject(error)
)

// ── 响应拦截器：统一处理业务状态码和错误 ──────────────────
request.interceptors.response.use(
  (response: AxiosResponse<Result<unknown>>) => {
    const res = response.data

    if (res.code === 200) {
      // 成功：直接返回 data，调用方拿到的就是业务数据，不需要再 .data.data
      return res.data as any
    }

    // 其他业务错误：弹出后端返回的错误信息（silentError=true 时跳过 toast，由调用方处理）
    if (!response.config.silentError) {
      ElMessage.error(res.message || '操作失败')
    }
    return Promise.reject(new Error(res.message))
  },
  async error => {
    // HTTP 401：accessToken 过期或无效，尝试用 refreshToken 换签
    if (error.response?.status === 401) {
      const originalRequest = error.config as InternalAxiosRequestConfig & { _retry?: boolean }
      const storedRefreshToken = localStorage.getItem('refreshToken')

      // refreshToken 不存在，或该请求已重试过一次（防止 refresh 接口本身返回 401 死循环）
      if (!storedRefreshToken || originalRequest._retry) {
        clearAuthAndRedirect()
        return Promise.reject(error)
      }

      if (!isRefreshing) {
        isRefreshing = true
        originalRequest._retry = true

        try {
          // 直接用 axios（不经过 request 拦截器），避免循环依赖和无限重试
          const resp = await axios.post('/api/user/refresh', { refreshToken: storedRefreshToken })
          const result = resp.data  // Result<UserLoginVO>

          if (result.code !== 200) {
            // refreshToken 已吊销（封号/改密码）或过期
            clearAuthAndRedirect()
            return Promise.reject(error)
          }

          const newAccessToken = result.data.accessToken
          localStorage.setItem('token', newAccessToken)

          // 通知所有排队请求使用新 token
          pendingRequests.forEach(cb => cb(newAccessToken))
          pendingRequests = []

          // 重试触发续签的原始请求
          originalRequest.headers['token'] = newAccessToken
          return request(originalRequest)
        } catch {
          clearAuthAndRedirect()
          return Promise.reject(error)
        } finally {
          isRefreshing = false
        }
      }

      // 已有 refresh 在途，当前请求排队等待新 token
      return new Promise(resolve => {
        pendingRequests.push((newToken: string) => {
          originalRequest._retry = true  // 防止重试后再次 401 时触发第二轮 refresh
          originalRequest.headers['token'] = newToken
          resolve(request(originalRequest))
        })
      })
    }

    // 网络错误、超时等 HTTP 层面的其他错误
    if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请检查网络后重试')
    } else if (error.response?.status !== 401) {
      ElMessage.error('网络异常，请稍后再试')
    }
    return Promise.reject(error)
  }
)

export default request
