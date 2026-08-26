import request from '@/utils/request'
import type {
  UserInfo, MerchantVO, ScheduleVO, BookingVO, CursorPage,
  ReviewVO, RushResultVO, PostVO, RushRecordVO, MerchantStatsVO,
  MessageVO, ConversationVO, QuestionnaireVO, PageResult
} from '@/types'

// ── 用户 ──────────────────────────────────────────────────
export const userApi = {
  login: (data: { phone: string; password: string }) =>
    request.post<any, { token: string; userInfo: UserInfo }>('/user/login', data),

  register: (data: { phone: string; nickname: string; password: string }) =>
    request.post<any, void>('/user/register', data),
}

// ── 商家 ──────────────────────────────────────────────────
export const merchantApi = {
  getById: (id: number) =>
    request.get<any, MerchantVO>(`/merchant/${id}`),

  search: (params: { keyword?: string; city?: string; serviceType?: number; page: number; size: number }) =>
    request.get<any, { records: MerchantVO[]; total: number }>('/merchant/search', { params }),

  updateInfo: (data: Partial<{
    serviceTypes: number[]; city: string; intro: string;
    alipayLink: string; xianyuLink: string; xiaohongshuLink: string; weiboLink: string;
    priceMin: number; priceMax: number; bookingNotice: string;
  }>) => request.put<any, void>('/merchant/info', data),

  getMyInfo: () =>
    request.get<any, MerchantVO>('/merchant/my'),

  getStats: () =>
    request.get<any, MerchantStatsVO>('/booking/stats/merchant'),
}

// ── 档期 ──────────────────────────────────────────────────
export const scheduleApi = {
  listByMonth: (merchantId: number, month: string) =>
    request.get<any, ScheduleVO[]>(`/schedule/merchant/${merchantId}`, { params: { month } }),

  create: (data: {
    date: string; timeSlot: string; bookType: 0 | 1; serviceType: number;
    rushOpenTime?: string; maxQueueSize?: number
  }) => request.post<any, void>('/schedule', data),

  batchCreate: (data: {
    startDate: string; endDate: string; weekdays: number[];
    timeSlot: string; bookType: 0 | 1; serviceType: number;
    rushOpenTime?: string; maxQueueSize?: number
  }) => request.post<any, void>('/schedule/batch', data),

  deleteSchedule: (id: number) =>
    request.delete<any, void>(`/schedule/${id}`),

  rush: (scheduleId: number) =>
    request.post<any, RushResultVO>(`/schedule/${scheduleId}/rush`),

  getQueue: (scheduleId: number) =>
    request.get<any, RushRecordVO[]>(`/schedule/${scheduleId}/queue`),

  updateRushStatus: (rushId: number, status: number) =>
    request.put<any, void>(`/schedule/rush/${rushId}/status`, undefined, { params: { status } }),
}

// ── 预约 ──────────────────────────────────────────────────
export const bookingApi = {
  create: (data: { scheduleId: number; remark?: string; questionnaireAnswer?: string }) =>
    request.post<any, void>('/booking', data),

  myBookings: (params: { lastId?: number | null; size: number; serviceType?: number | null }) =>
    request.get<any, CursorPage<BookingVO>>('/booking/my', { params }),

  receivedBookings: (params: { lastId?: number | null; size: number; serviceType?: number | null; status?: number | null }) =>
    request.get<any, CursorPage<BookingVO>>('/booking/received', { params }),

  confirm: (id: number) => request.put<any, void>(`/booking/${id}/confirm`),
  complete: (id: number) => request.put<any, void>(`/booking/${id}/complete`),
  cancel: (id: number) => request.put<any, void>(`/booking/${id}/cancel`),
}

// ── 评价 ──────────────────────────────────────────────────
export const reviewApi = {
  submit: (data: { orderId: number; score: number; content: string }) =>
    request.post<any, void>('/review', data),

  listByMerchant: (merchantId: number, params: { page: number; size: number }) =>
    request.get<any, { records: ReviewVO[]; total: number }>(`/review/merchant/${merchantId}`, { params }),

  reply: (id: number, data: { reply: string }) =>
    request.put<any, void>(`/review/${id}/reply`, data),
}

// ── 动态 ──────────────────────────────────────────────────
export const postApi = {
  create: (data: { content: string; images?: string[] }) =>
    request.post<any, void>('/post', data),

  listByMerchant: (merchantId: number, params: { lastId?: number | null; size: number }) =>
    request.get<any, CursorPage<PostVO>>(`/post/merchant/${merchantId}`, { params }),

  feed: (params: { lastId?: number | null; size: number }) =>
    request.get<any, CursorPage<PostVO>>('/post/feed', { params }),

  followedFeed: (params: { lastId?: number | null; size: number }) =>
    request.get<any, CursorPage<PostVO>>('/post/followed-feed', { params }),

  toggleLike: (id: number) =>
    request.post<any, void>(`/post/${id}/like`),

  delete: (id: number) =>
    request.delete<any, void>(`/post/${id}`),
}

// ── 关注 ──────────────────────────────────────────────────
export const followApi = {
  follow: (merchantId: number) =>
    request.post<any, void>(`/follow/${merchantId}`),

  unfollow: (merchantId: number) =>
    request.delete<any, void>(`/follow/${merchantId}`),

  isFollowing: (merchantId: number) =>
    request.get<any, boolean>(`/follow/${merchantId}/status`),

  myFollows: () =>
    request.get<any, MerchantVO[]>('/follow/my'),
}

// ── 消息 ──────────────────────────────────────────────────
export const messageApi = {
  send: (data: { toUserId: number; content: string; msgType?: 0 | 1 }) =>
    request.post<any, void>('/message/send', data),

  history: (params: { targetUserId: number; lastId?: number | null; size: number }) =>
    request.get<any, CursorPage<MessageVO>>('/message/history', { params }),

  getConversations: () =>
    request.get<any, ConversationVO[]>('/message/conversations'),
}

// ── 问卷 ──────────────────────────────────────────────────
export const questionnaireApi = {
  getByMerchant: (merchantId: number) =>
    request.get<any, QuestionnaireVO[]>(`/questionnaire/merchant/${merchantId}`),

  getMyTemplates: () =>
    request.get<any, QuestionnaireVO[]>('/questionnaire/my'),

  create: (data: { title: string; questions: any[]; isRequired: boolean }) =>
    request.post<any, void>('/questionnaire', {
      title: data.title,
      questions: JSON.stringify(data.questions),
      isRequired: data.isRequired ? 1 : 0
    }),

  delete: (id: number) =>
    request.delete<any, void>(`/questionnaire/${id}`),
}

// ── 投诉 ──────────────────────────────────────────────────
export const complaintApi = {
  submit: (data: { orderId: number; reason: string; evidence?: string[] }) =>
    request.post<any, void>('/complaint', data),
}

// ── 图片上传 ──────────────────────────────────────────────
export const uploadApi = {
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request.post<any, string>('/upload', form)
  },
}

// ── AI 助手 ───────────────────────────────────────────────
// chat 返回原始 fetch Response，调用方自行处理 SSE 流；不走 axios 是因为 axios 不支持流式读取
export const aiApi = {
  chat: (message: string): Promise<Response> =>
    fetch('/api/ai/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        token: localStorage.getItem('token') ?? '',
      },
      body: JSON.stringify({ message }),
    }),

  clearHistory: () => request.delete<any, void>('/ai/clear'),
}
