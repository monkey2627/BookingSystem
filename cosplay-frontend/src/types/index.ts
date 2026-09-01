// 后端统一响应体，对应 Java 的 Result<T>
export interface Result<T> {
  code: number
  message: string
  data: T
}

// 游标分页响应体，对应 Java 的 CursorPageVO<T>
export interface CursorPage<T> {
  list: T[]
  hasMore: boolean
  nextCursor: number | null
}

// MyBatis-Plus 页码分页响应体
export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
}

// 用户登录后的信息，存在 Pinia store 里
export interface UserInfo {
  id: number
  nickname: string
  avatar: string
}

// 服务类型枚举（7 类）
export const SERVICE_TYPE_MAP: Record<number, string> = {
  1: '妆',
  2: '摄影',
  3: '假发',
  4: '影棚',
  5: '后勤',
  6: '后期',
  7: '其他'
}

// 预约状态枚举
export const BOOKING_STATUS_MAP: Record<number, { label: string; type: 'info' | 'warning' | 'success' | 'danger' | '' }> = {
  0: { label: '待确认', type: 'info' },
  1: { label: '待付款', type: 'warning' },
  2: { label: '已定档', type: '' },
  3: { label: '已完成', type: 'success' },
  4: { label: '已取消', type: 'danger' }
}

// 档期状态
export const SCHEDULE_STATUS_MAP: Record<number, { label: string; color: string }> = {
  0: { label: '可预约', color: '#67c23a' },
  1: { label: '已预约', color: '#e6a23c' },
  2: { label: '不可用', color: '#909399' }
}

// 排队状态
export const RUSH_STATUS_MAP: Record<number, { label: string; type: 'primary' | 'success' | 'warning' | 'danger' | 'info' }> = {
  0: { label: '排队中', type: 'primary' },
  1: { label: '已联系', type: 'warning' },
  2: { label: '已转化', type: 'success' },
  3: { label: '已放弃', type: 'danger' }
}

// ── VO 类型（对应后端各 VO 类） ──────────────────────────

export interface MerchantVO {
  id: number
  userId: number
  nickname: string
  avatar: string
  serviceTypes: number[]
  city: string
  intro: string
  avgScore: number
  reviewCount: number
  alipayLink: string
  xianyuLink: string
  xiaohongshuLink: string
  weiboLink: string
  priceMin: number | null
  priceMax: number | null
  bookingNotice: string
  status: number               // 1=营业中，0=已关闭
  scheduleVisibility: number   // 0=全公开，1=仅忙闲，2=完全私密
}

export interface ScheduleVO {
  id: number
  merchantId: number
  date: string            // 'YYYY-MM-DD'
  timeSlot: string
  status: 0 | 1 | 2
  bookType: 0 | 1
  serviceType: number
  rushOpenTime: string | null
  maxQueueSize: number
  currentQueueSize: number
}

export interface BookingVO {
  id: number
  orderNo: string
  merchantId: number
  merchantUserId: number
  userId: number
  scheduleDate: string
  timeSlot: string
  status: 0 | 1 | 2 | 3 | 4
  serviceType: number
  remark: string
  merchantNickname: string
  userNickname: string
  createTime: string
  questionnaireAnswer: string | null
}

export interface ReviewVO {
  id: number
  orderId: number
  userId: number
  merchantId: number
  score: number
  content: string
  images: string[]
  reply: string
  createTime: string
  userNickname: string
}

export interface RushResultVO {
  rankNo: number
  message: string
}

// 动态 VO
export interface PostVO {
  id: number
  merchantId: number
  merchantNickname: string
  merchantAvatar: string
  content: string
  images: string[]
  likeCount: number
  liked: boolean          // 当前用户是否已点赞
  createTime: string
}

// 排队记录 VO（商家查看队列用）
export interface RushRecordVO {
  id: number
  scheduleId: number
  userId: number
  userNickname: string
  rankNo: number
  status: 0 | 1 | 2 | 3
  rushTime: string
}

// 商家数据统计 VO
export interface MerchantStatsVO {
  totalOrders: number
  completedOrders: number
  cancelledOrders: number
  pendingOrders: number
  avgScore: number
  reviewCount: number
}

// 会话列表 VO
export interface ConversationVO {
  userId: number
  nickname: string
  avatar: string
  lastMessage: string
  unreadCount: number
  lastTime: string
}

// 消息 VO
export interface MessageVO {
  id: number
  fromUserId: number
  toUserId: number
  content: string
  msgType: 0 | 1       // 0=文字 1=图片
  isRead: boolean
  createTime: string
  fromNickname?: string
  fromAvatar?: string
}

// 抢档大厅条目 VO
export interface RushLobbyItemVO {
  scheduleId: number
  merchantId: number
  merchantNickname: string
  merchantAvatar: string
  serviceTypes: number[]
  date: string
  timeSlot: string
  serviceType: number
  rushOpenTime: string | null
  maxQueueSize: number
  currentQueueSize: number
  open: boolean
}

// 问卷模板 VO
export interface QuestionnaireVO {
  id: number
  merchantId: number
  title: string
  questions: QuestionItem[]
  isRequired: boolean
  createTime: string
}

export interface QuestionItem {
  id: string
  label: string          // 题目文字
  type: 'text' | 'radio' | 'checkbox'
  options?: string[]     // radio/checkbox 的选项
  required: boolean
}

export interface ComplaintVO {
  id: number
  orderId: number
  complainantId: number
  complainantNickname: string
  reason: string
  evidence?: string
  status: 0 | 1 | 2      // 0=待处理 1=处理中 2=已处理
  adminReply?: string
  createTime: string
}
