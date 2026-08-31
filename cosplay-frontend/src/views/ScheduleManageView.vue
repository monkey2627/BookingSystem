<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">档期管理</h2>
      <div class="header-actions">
        <el-button @click="openBatchDialog">批量创建</el-button>
        <el-button type="primary" @click="openCreateDialog(null)">创建档期</el-button>
      </div>
    </div>

    <!-- 月份导航 -->
    <el-card class="calendar-card">
      <div class="calendar-header">
        <el-button :icon="ArrowLeft" circle @click="prevMonth" />
        <span class="month-label">{{ currentMonth }}</span>
        <el-button :icon="ArrowRight" circle @click="nextMonth" />
      </div>

      <!-- 图例 -->
      <div class="legend">
        <span class="legend-item"><span class="dot" style="background:#67c23a"></span> 空闲可预约</span>
        <span class="legend-item"><span class="dot" style="background:#e6a23c"></span> 已有预约</span>
        <span class="legend-item"><span class="dot" style="background:#409eff"></span> 抢档期</span>
        <span class="legend-item"><span class="dot" style="background:#909399"></span> 不可用</span>
      </div>

      <div class="week-header">
        <span v-for="d in ['日','一','二','三','四','五','六']" :key="d">{{ d }}</span>
      </div>

      <div class="calendar-grid" v-loading="loading">
        <div v-for="(day, idx) in calendarDays" :key="idx"
          :class="['day-cell', day ? getDayClass(day.dateStr) : 'empty']"
          @click="day && handleDayClick(day.dateStr)">
          <template v-if="day">
            <span class="day-num">{{ day.num }}</span>
            <template v-if="scheduleMap[day.dateStr]">
              <span class="day-slot">{{ scheduleMap[day.dateStr].timeSlot || '全天' }}</span>
              <!-- 档期类型角标 -->
              <span v-if="scheduleMap[day.dateStr].bookType === 1" class="rush-badge">抢</span>
            </template>
          </template>
        </div>
      </div>
    </el-card>

    <!-- ── 单个创建/查看弹窗 ── -->
    <el-dialog v-model="createDialogVisible"
      :title="selectedSchedule ? '档期详情' : `创建档期 — ${selectedDate}`"
      width="480px" @close="resetCreate">

      <!-- 已有档期：显示操作 -->
      <div v-if="selectedSchedule" class="schedule-detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="日期">{{ selectedSchedule.date }}</el-descriptions-item>
          <el-descriptions-item label="时间段">{{ selectedSchedule.timeSlot || '全天' }}</el-descriptions-item>
          <el-descriptions-item label="模式">
            {{ selectedSchedule.bookType === 0 ? '直接预约' : '抢档期' }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(selectedSchedule.status)">
              {{ ['空闲','已预约','不可用'][selectedSchedule.status] }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="selectedSchedule.bookType === 1" label="当前排队">
            {{ selectedSchedule.currentQueueSize }} 人
          </el-descriptions-item>
        </el-descriptions>

        <!-- 抢档期查看队列按钮 -->
        <el-button v-if="selectedSchedule.bookType === 1" class="queue-btn"
          type="primary" plain @click="openQueueDrawer(selectedSchedule)">
          查看排队名单（{{ selectedSchedule.currentQueueSize }}人）
        </el-button>
      </div>

      <!-- 新建档期表单 -->
      <el-form v-else :model="createForm" :rules="createRules" ref="createFormRef" label-width="90px">
        <el-form-item label="服务类型" prop="serviceType">
          <el-select v-model="createForm.serviceType" placeholder="请选择服务类型" style="width:100%">
            <el-option v-for="(name, val) in SERVICE_TYPE_MAP" :key="val" :label="name" :value="Number(val)" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间段" prop="timeSlot">
          <el-input v-model="createForm.timeSlot" placeholder="如 09:00-12:00，留空表示全天" />
        </el-form-item>
        <el-form-item label="预约模式" prop="bookType">
          <el-radio-group v-model="createForm.bookType">
            <el-radio :value="0">直接预约</el-radio>
            <el-radio :value="1">抢档期</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="createForm.bookType === 1">
          <el-form-item label="开放时间" prop="rushOpenTime">
            <el-date-picker v-model="createForm.rushOpenTime" type="datetime"
              placeholder="抢档期开放时间" style="width:100%" />
          </el-form-item>
          <el-form-item label="最大排队" prop="maxQueueSize">
            <el-input-number v-model="createForm.maxQueueSize" :min="1" :max="100" />
          </el-form-item>
        </template>
      </el-form>

      <template #footer>
        <template v-if="selectedSchedule">
          <el-button v-if="selectedSchedule.status === 0" type="danger" plain
            :loading="deleteLoading" @click="handleDelete(selectedSchedule.id)">
            删除档期
          </el-button>
          <el-button @click="createDialogVisible = false">关闭</el-button>
        </template>
        <template v-else>
          <el-button @click="createDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="createLoading" @click="handleCreate">创建</el-button>
        </template>
      </template>
    </el-dialog>

    <!-- ── 批量创建弹窗 ── -->
    <el-dialog v-model="batchDialogVisible" title="批量创建档期" width="520px" @close="resetBatch">
      <el-form :model="batchForm" :rules="batchRules" ref="batchFormRef" label-width="90px">
        <el-form-item label="服务类型" prop="serviceType">
          <el-select v-model="batchForm.serviceType" placeholder="请选择服务类型" style="width:100%">
            <el-option v-for="(name, val) in SERVICE_TYPE_MAP" :key="val" :label="name" :value="Number(val)" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期范围" prop="dateRange">
          <el-date-picker v-model="batchForm.dateRange" type="daterange"
            start-placeholder="开始日期" end-placeholder="结束日期" style="width:100%" />
        </el-form-item>
        <el-form-item label="限定星期">
          <el-checkbox-group v-model="batchForm.weekdays">
            <el-checkbox v-for="(w, i) in weekNames" :key="i+1" :value="i+1">{{ w }}</el-checkbox>
          </el-checkbox-group>
          <div class="form-tip">不选表示范围内每天都创建</div>
        </el-form-item>
        <el-form-item label="时间段">
          <el-input v-model="batchForm.timeSlot" placeholder="如 09:00-12:00，留空全天" />
        </el-form-item>
        <el-form-item label="预约模式" prop="bookType">
          <el-radio-group v-model="batchForm.bookType">
            <el-radio :value="0">直接预约</el-radio>
            <el-radio :value="1">抢档期</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="batchForm.bookType === 1">
          <el-form-item label="开放时间" prop="rushOpenTime">
            <el-date-picker v-model="batchForm.rushOpenTime" type="datetime"
              placeholder="抢档期统一开放时间" style="width:100%" />
          </el-form-item>
          <el-form-item label="最大排队">
            <el-input-number v-model="batchForm.maxQueueSize" :min="1" :max="100" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="batchDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchLoading" @click="handleBatch">批量创建</el-button>
      </template>
    </el-dialog>

    <!-- ── 排队名单抽屉 ── -->
    <el-drawer v-model="queueDrawerVisible" :title="`排队名单 — ${queueSchedule?.date}`"
      size="400px" direction="rtl">
      <div v-loading="queueLoading">
        <el-empty v-if="queue.length === 0 && !queueLoading" description="暂无排队记录" />
        <div v-for="r in queue" :key="r.id" class="queue-item">
          <div class="queue-rank">#{{ r.rankNo }}</div>
          <div class="queue-info">
            <div class="queue-name">{{ r.userNickname }}</div>
            <div class="queue-time">{{ r.rushTime?.slice(0, 16) }}</div>
          </div>
          <el-select v-model="r.status" size="small" style="width:100px"
            @change="(val: number) => handleStatusChange(r.id, val)">
            <el-option v-for="(s, k) in RUSH_STATUS_MAP" :key="k" :label="s.label" :value="Number(k)" />
          </el-select>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { scheduleApi, merchantApi } from '@/api'
import { RUSH_STATUS_MAP, SERVICE_TYPE_MAP, type ScheduleVO, type RushRecordVO } from '@/types'

// ── 当前商家 ID ───────────────────────────────────────────
const merchantId = ref<number | null>(null)
onMounted(async () => {
  try {
    const myInfo = await merchantApi.getMyInfo()
    merchantId.value = myInfo.id
  } catch { /* 非商家用户忽略 */ }
  await fetchSchedules()
})

// ── 月历 ──────────────────────────────────────────────────
const now = new Date()
const currentMonth = ref(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`)
const loading = ref(false)
const schedules = ref<ScheduleVO[]>([])

const scheduleMap = computed(() => {
  const map: Record<string, ScheduleVO> = {}
  schedules.value.forEach(s => { map[s.date] = s })
  return map
})

const calendarDays = computed(() => {
  const [year, month] = currentMonth.value.split('-').map(Number)
  const firstDay = new Date(year, month - 1, 1).getDay()
  const daysInMonth = new Date(year, month, 0).getDate()
  const days: (null | { num: number; dateStr: string })[] = []
  for (let i = 0; i < firstDay; i++) days.push(null)
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${year}-${String(month).padStart(2, '0')}-${String(d).padStart(2, '0')}`
    days.push({ num: d, dateStr })
  }
  return days
})

function getDayClass(dateStr: string) {
  const s = scheduleMap.value[dateStr]
  if (!s) return 'day-empty'
  if (s.status === 0 && s.bookType === 1) return 'day-rush'
  if (s.status === 0) return 'day-free'
  if (s.status === 1) return 'day-booked'
  return 'day-unavailable'
}

function statusType(status: number) {
  return ['success', 'warning', 'info'][status] as any
}

function prevMonth() {
  const [y, m] = currentMonth.value.split('-').map(Number)
  const d = new Date(y, m - 2, 1)
  currentMonth.value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
function nextMonth() {
  const [y, m] = currentMonth.value.split('-').map(Number)
  const d = new Date(y, m, 1)
  currentMonth.value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

watch(currentMonth, fetchSchedules)

async function fetchSchedules() {
  if (!merchantId.value) return
  loading.value = true
  try {
    schedules.value = await scheduleApi.listByMonth(merchantId.value, currentMonth.value)
  } finally {
    loading.value = false
  }
}

// ── 单个创建 ─────────────────────────────────────────────
const createDialogVisible = ref(false)
const selectedDate = ref('')
const selectedSchedule = ref<ScheduleVO | null>(null)
const createLoading = ref(false)
const deleteLoading = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = ref({ serviceType: null as number | null, timeSlot: '', bookType: 0 as 0 | 1, rushOpenTime: null as any, maxQueueSize: 10 })
const createRules: FormRules = {
  serviceType: [{ required: true, message: '请选择服务类型' }],
  bookType: [{ required: true }]
}

function handleDayClick(dateStr: string) {
  selectedDate.value = dateStr
  selectedSchedule.value = scheduleMap.value[dateStr] ?? null
  createDialogVisible.value = true
}

function openCreateDialog(date: string | null) {
  selectedDate.value = date ?? ''
  selectedSchedule.value = null
  createDialogVisible.value = true
}

function resetCreate() {
  createForm.value = { serviceType: null, timeSlot: '', bookType: 0, rushOpenTime: null, maxQueueSize: 10 }
}

async function handleCreate() {
  if (!await createFormRef.value?.validate().catch(() => false)) return
  createLoading.value = true
  try {
    await scheduleApi.create({
      date: selectedDate.value,
      timeSlot: createForm.value.timeSlot,
      bookType: createForm.value.bookType,
      serviceType: createForm.value.serviceType!,
      rushOpenTime: createForm.value.rushOpenTime
        ? new Date(createForm.value.rushOpenTime).toISOString().slice(0, 19)
        : undefined,
      maxQueueSize: createForm.value.maxQueueSize
    })
    ElMessage.success('创建成功')
    createDialogVisible.value = false
    fetchSchedules()
  } finally {
    createLoading.value = false
  }
}

async function handleDelete(id: number) {
  await ElMessageBox.confirm('确定删除该档期吗？', '提示', { type: 'warning' })
  deleteLoading.value = true
  try {
    await scheduleApi.deleteSchedule(id)
    ElMessage.success('已删除')
    createDialogVisible.value = false
    fetchSchedules()
  } finally {
    deleteLoading.value = false
  }
}

// ── 批量创建 ─────────────────────────────────────────────
const batchDialogVisible = ref(false)
const batchLoading = ref(false)
const batchFormRef = ref<FormInstance>()
const weekNames = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const batchForm = ref({
  serviceType: null as number | null,
  dateRange: null as any,
  weekdays: [] as number[],
  timeSlot: '',
  bookType: 0 as 0 | 1,
  rushOpenTime: null as any,
  maxQueueSize: 10
})
const batchRules: FormRules = {
  serviceType: [{ required: true, message: '请选择服务类型' }],
  dateRange: [{ required: true, message: '请选择日期范围' }],
  bookType: [{ required: true }]
}

function openBatchDialog() { batchDialogVisible.value = true }
function resetBatch() {
  batchForm.value = { serviceType: null, dateRange: null, weekdays: [], timeSlot: '', bookType: 0, rushOpenTime: null, maxQueueSize: 10 }
}

async function handleBatch() {
  if (!await batchFormRef.value?.validate().catch(() => false)) return
  batchLoading.value = true
  try {
    const [start, end] = batchForm.value.dateRange
    await scheduleApi.batchCreate({
      startDate: start.toISOString().slice(0, 10),
      endDate: end.toISOString().slice(0, 10),
      weekdays: batchForm.value.weekdays,
      timeSlot: batchForm.value.timeSlot,
      bookType: batchForm.value.bookType,
      serviceType: batchForm.value.serviceType!,
      rushOpenTime: batchForm.value.rushOpenTime
        ? new Date(batchForm.value.rushOpenTime).toISOString().slice(0, 19)
        : undefined,
      maxQueueSize: batchForm.value.maxQueueSize
    })
    ElMessage.success('批量创建完成')
    batchDialogVisible.value = false
    fetchSchedules()
  } finally {
    batchLoading.value = false
  }
}

// ── 排队名单 ─────────────────────────────────────────────
const queueDrawerVisible = ref(false)
const queueLoading = ref(false)
const queueSchedule = ref<ScheduleVO | null>(null)
const queue = ref<RushRecordVO[]>([])

async function openQueueDrawer(s: ScheduleVO) {
  queueSchedule.value = s
  queueDrawerVisible.value = true
  queueLoading.value = true
  try {
    queue.value = await scheduleApi.getQueue(s.id)
  } finally {
    queueLoading.value = false
  }
}

async function handleStatusChange(rushId: number, status: number) {
  try {
    await scheduleApi.updateRushStatus(rushId, status)
    ElMessage.success('状态已更新')
  } catch {
    /* 错误由 axios 拦截器弹出 */
  }
}
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { font-size: 20px; font-weight: 600; margin: 0; }
.header-actions { display: flex; gap: 8px; }

.calendar-card { }
.calendar-header { display: flex; align-items: center; justify-content: center; gap: 16px; margin-bottom: 12px; }
.month-label { font-size: 16px; font-weight: 600; min-width: 100px; text-align: center; }

.legend { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; font-size: 13px; color: #606266; }
.legend-item { display: flex; align-items: center; gap: 4px; }
.dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }

.week-header { display: grid; grid-template-columns: repeat(7, 1fr); text-align: center; font-size: 13px; color: #909399; margin-bottom: 4px; }

.calendar-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 4px; }
.day-cell {
  min-height: 70px;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  border-radius: 8px; font-size: 13px; cursor: pointer; position: relative;
  border: 1px solid transparent; transition: all 0.15s;
}
.day-cell:hover { border-color: #409eff; }
.empty { cursor: default; pointer-events: none; }
.day-num { font-size: 14px; font-weight: 500; }
.day-slot { font-size: 11px; color: #909399; margin-top: 2px; }
.rush-badge {
  position: absolute; top: 4px; right: 4px;
  background: #409eff; color: #fff; font-size: 10px;
  padding: 1px 4px; border-radius: 4px;
}

.day-free { background: #f0f9eb; color: #67c23a; }
.day-rush { background: #ecf5ff; color: #409eff; }
.day-booked { background: #fdf6ec; color: #e6a23c; }
.day-unavailable { background: #f4f4f5; color: #909399; text-decoration: line-through; }
.day-empty { color: #c0c4cc; }

.schedule-detail .queue-btn { margin-top: 12px; width: 100%; }

.form-tip { font-size: 12px; color: #909399; margin-top: 4px; }

.queue-item { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid #f0f0f0; }
.queue-rank { font-size: 20px; font-weight: 700; color: #409eff; min-width: 36px; text-align: center; }
.queue-info { flex: 1; }
.queue-name { font-weight: 500; font-size: 14px; }
.queue-time { font-size: 12px; color: #909399; }
</style>
