import axios from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { Result } from '@/types'

// 扩展 Axios 请求配置类型，支持 silentError 选项
// silentError=true：业务错误不弹 toast，由调用方自己处理（适用于"预期内可能失败"的初始化请求）
declare module 'axios' {
  interface AxiosRequestConfig {
    silentError?: boolean
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

// ── 请求拦截器：每个请求发出前自动加 token ──────────────────
request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 从 localStorage 读 token（Pinia store 里也会持久化到这里）
    // 不从 Pinia 直接读是为了避免循环依赖（store 会 import request，request 不应再 import store）
    const token = localStorage.getItem('token')
    if (token) {
      config.headers['token'] = token  // 后端 sa-token 配置的 token-name = 'token'
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

    if (res.code === 401) {
      // 未登录或 token 过期：清除本地凭证，跳转登录页
      // 用 window.location 而不是 router.push，确保彻底重置 Vue 应用状态
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      ElMessage.error('登录已过期，请重新登录')
      window.location.href = '/login'
      return Promise.reject(new Error('未登录'))
    }

    // 其他业务错误：弹出后端返回的错误信息（silentError=true 时跳过 toast，由调用方处理）
    if (!response.config.silentError) {
      ElMessage.error(res.message || '操作失败')
    }
    return Promise.reject(new Error(res.message))
  },
  error => {
    // 网络错误、超时等 HTTP 层面的错误
    if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请检查网络后重试')
    } else {
      ElMessage.error('网络异常，请稍后再试')
    }
    return Promise.reject(error)
  }
)

export default request
