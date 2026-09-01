<template>
  <div class="page-container">
    <!-- ── 买卖视角切换 ── -->
    <div class="perspective-switch">
      <span :class="['ps-tab', !isMerchantView && 'active']" @click="$router.push('/orders/my')">
        我预定的
      </span>
      <span :class="['ps-tab', isMerchantView && 'active']" @click="$router.push('/orders/received')">
        我卖出的
      </span>
    </div>

    <!-- ── 服务类型筛选（投诉 tab 时隐藏） ── -->
    <div v-if="selectedStatusStr !== 'complaints'" class="type-filter">
      <el-check-tag :checked="selectedType === null" @click="setType(null)" class="type-chip">全部</el-check-tag>
      <el-check-tag
        v-for="(name, val) in SERVICE_TYPE_MAP"
        :key="val"
        :checked="selectedType === Number(val)"
        @click="setType(Number(val))"
        class="type-chip">
        {{ name }}
      </el-check-tag>
    </div>

    <!-- ── 状态 Tab（仅卖家视角） ── -->
    <div v-if="isMerchantView" class="status-tabs-wrap">
      <el-tabs v-model="selectedStatusStr" @tab-change="onStatusChange">
        <el-tab-pane label="全部"     name="all" />
        <el-tab-pane label="待确认"   name="0" />
        <el-tab-pane label="待完成"   name="2" />
        <el-tab-pane label="已完成"   name="3" />
        <el-tab-pane label="已取消"   name="4" />
        <el-tab-pane label="收到的投诉" name="complaints" />
      </el-tabs>
    </div>

    <!-- ── 未开通店铺提示（仅卖家视角） ── -->
    <div v-if="isMerchantView && merchantId === -1" class="no-merchant-wrap">
      <el-empty description="您还没有开通店铺，暂无收到的预约">
        <el-button type="primary" @click="router.push('/merchant/profile')">去开通店铺</el-button>
      </el-empty>
    </div>

    <!-- ── 投诉列表（仅卖家投诉 Tab） ── -->
    <template v-else-if="selectedStatusStr === 'complaints'">
      <div v-if="complaintsLoading" class="empty-wrap"><el-icon class="is-loading"><Loading /></el-icon></div>
      <div v-else-if="complaints.length === 0" class="empty-wrap">
        <el-empty description="暂无收到的投诉" />
      </div>
      <div v-else class="booking-list">
        <el-card v-for="c in complaints" :key="c.id" class="booking-card complaint-card">
          <div class="booking-header">
            <span class="booking-no">预约 #{{ c.orderId }}</span>
            <el-tag size="small" :type="COMPLAINT_STATUS_TYPE[c.status]">
              {{ COMPLAINT_STATUS_LABEL[c.status] }}
            </el-tag>
          </div>
          <div class="booking-info" style="gap:8px; font-size:14px; color:#606266;">
            <div><el-icon><User /></el-icon> 投诉方：{{ c.complainantNickname }}</div>
            <div class="complaint-reason">{{ c.reason }}</div>
            <div v-if="c.adminReply" class="admin-reply">
              <el-icon><ChatDotRound /></el-icon> 平台回复：{{ c.adminReply }}
            </div>
            <div class="create-time">{{ formatTime(c.createTime) }}</div>
          </div>
        </el-card>
      </div>
    </template>

    <!-- ── 空状态（普通预约列表） ── -->
    <div v-else-if="bookings.length === 0 && !loading" class="empty-wrap">
      <el-empty description="暂无订单" />
    </div>

    <!-- ── 订单列表 ── -->
    <div v-else class="booking-list">
      <el-card v-for="booking in bookings" :key="booking.id" class="booking-card">
        <div class="booking-header">
          <span class="booking-no">{{ booking.orderNo }}</span>
          <div class="header-tags">
            <el-tag v-if="booking.serviceType" size="small" effect="plain" class="type-tag">
              {{ SERVICE_TYPE_MAP[booking.serviceType] }}
            </el-tag>
            <el-tag size="small" :type="BOOKING_STATUS_MAP[booking.status]?.type">
              {{ BOOKING_STATUS_MAP[booking.status]?.label }}
            </el-tag>
          </div>
        </div>

        <div class="booking-body">
          <div class="booking-info">
            <div v-if="isMerchantView">
              <el-icon><User /></el-icon> 客人：{{ booking.userNickname }}
            </div>
            <div v-else>
              <el-icon><Shop /></el-icon> 商家：{{ booking.merchantNickname }}
            </div>
            <div><el-icon><Calendar /></el-icon> {{ booking.scheduleDate }} {{ booking.timeSlot }}</div>
            <div v-if="booking.remark" class="remark">
              <el-icon><ChatDotRound /></el-icon> {{ booking.remark }}
            </div>
            <div class="create-time">{{ formatTime(booking.createTime) }}</div>
          </div>

          <div class="booking-actions">
            <!-- 卖家操作 -->
            <template v-if="isMerchantView">
              <el-button v-if="booking.status === 0" type="primary" size="small"
                @click="handleConfirm(booking.id)">确认</el-button>
              <el-button v-if="booking.status === 2" type="success" size="small"
                @click="handleComplete(booking.id)">✓ 完成</el-button>
              <el-button size="small" plain
                @click="router.push({ path: '/messages', query: { userId: booking.userId, nickname: booking.userNickname } })">联系客人</el-button>
            </template>

            <!-- 买家操作 -->
            <template v-else>
              <el-button
                v-if="booking.status === 3 && !reviewedSet.has(booking.id)"
                type="warning" size="small" plain
                @click="openReviewDialog(booking)">写评价</el-button>
              <el-tag v-if="booking.status === 3 && reviewedSet.has(booking.id)" type="info" size="small">
                已评价
              </el-tag>
              <el-button
                v-if="booking.status === 3 || booking.status === 4"
                size="small" type="danger" plain
                @click="openComplaintDialog(booking)">投诉</el-button>
              <el-button size="small" plain
                @click="router.push({ path: '/messages', query: { userId: booking.merchantUserId, nickname: booking.merchantNickname } })">联系商家</el-button>
            </template>

            <el-button v-if="canCancel(booking)" type="danger" size="small" plain
              @click="handleCancel(booking.id)">取消</el-button>
          </div>
        </div>
      </el-card>
    </div>

    <div class="load-more" v-if="hasMore && selectedStatusStr !== 'complaints'">
      <el-button :loading="loading" @click="loadMore">加载更多</el-button>
    </div>
    <div class="no-more" v-if="!hasMore && bookings.length > 0 && selectedStatusStr !== 'complaints'">没有更多了</div>

    <!-- ── 投诉对话框 ── -->
    <el-dialog v-model="complaintDialogVisible" title="提交投诉" width="480px" @close="resetComplaintForm">
      <el-form :model="complaintForm" :rules="complaintRules" ref="complaintFormRef" label-width="80px">
        <el-form-item label="投诉原因" prop="reason">
          <el-input v-model="complaintForm.reason" type="textarea" :rows="4"
            placeholder="请详细描述投诉原因，有助于我们更快处理" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="complaintDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="complaintLoading" @click="handleSubmitComplaint">提交投诉</el-button>
      </template>
    </el-dialog>

    <!-- ── 评价对话框 ── -->
    <el-dialog v-model="reviewDialogVisible" title="写评价" width="480px" @close="resetReviewForm">
      <el-form :model="reviewForm" :rules="reviewRules" ref="reviewFormRef" label-width="70px">
        <el-form-item label="综合评分" prop="score">
          <el-rate v-model="reviewForm.score" :texts="['很差', '较差', '一般', '不错', '很棒']"
            show-text style="margin-top: 4px" />
        </el-form-item>
        <el-form-item label="评价内容" prop="content">
          <el-input v-model="reviewForm.content" type="textarea" :rows="4"
            placeholder="说说你的体验，帮助其他客人做决定~" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="reviewLoading" @click="handleSubmitReview">提交评价</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { User, Shop, Calendar, ChatDotRound, Loading } from '@element-plus/icons-vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { bookingApi, reviewApi, complaintApi, merchantApi } from '@/api'
import { BOOKING_STATUS_MAP, SERVICE_TYPE_MAP, type BookingVO, type ComplaintVO } from '@/types'

const COMPLAINT_STATUS_LABEL: Record<number, string> = { 0: '待处理', 1: '处理中', 2: '已处理' }
const COMPLAINT_STATUS_TYPE: Record<number, 'info' | 'warning' | 'success'> = { 0: 'info', 1: 'warning', 2: 'success' }

const route = useRoute()
const router = useRouter()

const isMerchantView = computed(() => route.name === 'receivedBookings')

// null=加载中，-1=无商家资料，正数=已有商家
const merchantId = ref<number | null>(null)

async function checkMerchant() {
  if (!isMerchantView.value) return
  try {
    const info = await merchantApi.getMyInfo()
    merchantId.value = info.id
  } catch {
    merchantId.value = -1
  }
}

// ── 筛选状态 ──────────────────────────────────────────────
const selectedType = ref<number | null>(null)
const selectedStatusStr = ref<string>('all')

function setType(val: number | null) {
  selectedType.value = val
  fetchBookings(null)
}

function onStatusChange() {
  if (selectedStatusStr.value === 'complaints') {
    fetchComplaints()
  } else {
    fetchBookings(null)
  }
}

// ── 列表数据 ──────────────────────────────────────────────
const bookings = ref<BookingVO[]>([])
const loading = ref(false)
const hasMore = ref(false)
const nextCursor = ref<number | null>(null)
const reviewedSet = ref(new Set<number>())

function canCancel(booking: BookingVO): boolean {
  if (isMerchantView.value) return booking.status !== 3 && booking.status !== 4
  return booking.status === 0 || booking.status === 1
}

function formatTime(str: string): string {
  return str?.replace('T', ' ').slice(0, 16) ?? ''
}

async function fetchBookings(cursor: number | null = null) {
  loading.value = true
  try {
    const statusNum = selectedStatusStr.value === 'all' ? undefined : Number(selectedStatusStr.value)
    const typeNum = selectedType.value ?? undefined

    const data = isMerchantView.value
      ? await bookingApi.receivedBookings({ lastId: cursor, size: 10, serviceType: typeNum, status: statusNum })
      : await bookingApi.myBookings({ lastId: cursor, size: 10, serviceType: typeNum })

    if (cursor === null) {
      bookings.value = data.list
    } else {
      bookings.value.push(...data.list)
    }
    hasMore.value = data.hasMore
    nextCursor.value = data.nextCursor
  } finally {
    loading.value = false
  }
}

function loadMore() { fetchBookings(nextCursor.value) }

// 切换买/卖视角时重置筛选，切到卖家视角时顺带检查是否有商家资料
watch(isMerchantView, async (isMerchant) => {
  selectedType.value = null
  selectedStatusStr.value = 'all'
  if (isMerchant) {
    await checkMerchant()
    if (merchantId.value === -1) return
  }
  fetchBookings(null)
})

// ── 收到的投诉 ────────────────────────────────────────────
const complaints = ref<ComplaintVO[]>([])
const complaintsLoading = ref(false)

async function fetchComplaints() {
  complaintsLoading.value = true
  try {
    complaints.value = await complaintApi.listReceived()
  } finally {
    complaintsLoading.value = false
  }
}

// ── 操作 ──────────────────────────────────────────────────
async function handleConfirm(id: number) {
  await bookingApi.confirm(id)
  ElMessage.success('已确认预约')
  refreshBooking(id, 2)
}

async function handleComplete(id: number) {
  await bookingApi.complete(id)
  ElMessage.success('已标记为完成')
  refreshBooking(id, 3)
}

async function handleCancel(id: number) {
  await ElMessageBox.confirm('确定要取消这个预约吗？', '取消确认', {
    confirmButtonText: '确定取消',
    cancelButtonText: '再想想',
    type: 'warning'
  })
  await bookingApi.cancel(id)
  ElMessage.success('预约已取消')
  refreshBooking(id, 4)
}

function refreshBooking(id: number, newStatus: number) {
  const b = bookings.value.find(b => b.id === id)
  if (b) b.status = newStatus as BookingVO['status']
}

// ── 投诉 ──────────────────────────────────────────────────
const complaintDialogVisible = ref(false)
const complaintLoading = ref(false)
const complaintFormRef = ref<FormInstance>()
const currentComplaintOrder = ref<BookingVO | null>(null)
const complaintForm = ref({ reason: '' })
const complaintRules: FormRules = {
  reason: [
    { required: true, message: '请填写投诉原因' },
    { min: 10, message: '投诉原因至少 10 个字' }
  ]
}

function openComplaintDialog(order: BookingVO) {
  currentComplaintOrder.value = order
  complaintForm.value = { reason: '' }
  complaintDialogVisible.value = true
}

function resetComplaintForm() {
  complaintFormRef.value?.resetFields()
  currentComplaintOrder.value = null
}

async function handleSubmitComplaint() {
  if (!await complaintFormRef.value?.validate().catch(() => false)) return
  if (!currentComplaintOrder.value) return
  complaintLoading.value = true
  try {
    await complaintApi.submit({ orderId: currentComplaintOrder.value.id, reason: complaintForm.value.reason })
    ElMessage.success('投诉已提交，我们将尽快处理')
    complaintDialogVisible.value = false
  } finally {
    complaintLoading.value = false
  }
}

// ── 评价 ──────────────────────────────────────────────────
const reviewDialogVisible = ref(false)
const reviewLoading = ref(false)
const reviewFormRef = ref<FormInstance>()
const currentReviewOrder = ref<BookingVO | null>(null)
const reviewForm = ref({ score: 5, content: '' })
const reviewRules: FormRules = {
  score: [{ required: true, type: 'number', min: 1, message: '请选择评分' }],
  content: [
    { required: true, message: '请填写评价内容' },
    { min: 5, message: '评价内容至少 5 个字' }
  ]
}

function openReviewDialog(order: BookingVO) {
  currentReviewOrder.value = order
  reviewForm.value = { score: 5, content: '' }
  reviewDialogVisible.value = true
}

function resetReviewForm() {
  reviewFormRef.value?.resetFields()
  currentReviewOrder.value = null
}

async function handleSubmitReview() {
  if (!await reviewFormRef.value?.validate().catch(() => false)) return
  if (!currentReviewOrder.value) return
  reviewLoading.value = true
  try {
    await reviewApi.submit({
      orderId: currentReviewOrder.value.id,
      score: reviewForm.value.score,
      content: reviewForm.value.content
    })
    ElMessage.success('评价已提交，感谢你的反馈！')
    reviewedSet.value.add(currentReviewOrder.value.id)
    reviewDialogVisible.value = false
  } finally {
    reviewLoading.value = false
  }
}

onMounted(async () => {
  await checkMerchant()
  if (isMerchantView.value && merchantId.value === -1) return
  fetchBookings()
})
</script>

<style scoped>
.perspective-switch {
  display: flex;
  gap: 0;
  margin-bottom: 16px;
  border-bottom: 2px solid #e4e7ed;
}

.ps-tab {
  padding: 10px 24px;
  font-size: 15px;
  color: #606266;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
  transition: all 0.2s;
  user-select: none;
}

.ps-tab.active {
  color: #409eff;
  font-weight: 600;
  border-bottom-color: #409eff;
}

.type-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.type-chip { cursor: pointer; }

.status-tabs-wrap { margin-bottom: 4px; }
.status-tabs-wrap :deep(.el-tabs__header) { margin-bottom: 12px; }

.booking-list { display: flex; flex-direction: column; gap: 12px; }
.booking-card { transition: box-shadow 0.2s; }
.booking-card:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.08); }

.booking-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.booking-no { font-size: 12px; color: #909399; font-family: monospace; }
.header-tags { display: flex; gap: 6px; align-items: center; }

.booking-body {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
}

.booking-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 14px;
  color: #606266;
  flex: 1;
}

.booking-info > div { display: flex; align-items: center; gap: 6px; }
.remark { color: #909399; }
.create-time { font-size: 12px; color: #c0c4cc; }
.booking-actions { display: flex; gap: 8px; flex-shrink: 0; align-items: center; }

.load-more { text-align: center; margin-top: 24px; }
.no-more { text-align: center; color: #c0c4cc; font-size: 13px; margin-top: 24px; }
.empty-wrap { padding: 40px 0; display: flex; flex-direction: column; align-items: center; gap: 16px; }
.no-merchant-wrap { padding: 60px 0; display: flex; justify-content: center; }

.complaint-card .complaint-reason {
  background: #f5f7fa;
  border-radius: 4px;
  padding: 8px 12px;
  color: #303133;
  line-height: 1.6;
}
.complaint-card .admin-reply {
  color: #409eff;
  display: flex;
  align-items: flex-start;
  gap: 6px;
}
</style>
