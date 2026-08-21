<template>
  <div class="page-container" v-loading="statsLoading">
    <h2 class="page-title">数据看板</h2>

    <!-- ── 数据统计卡片 ── -->
    <div class="stats-grid">
      <el-card class="stat-card" shadow="never">
        <div class="stat-value">{{ stats?.totalOrders ?? '--' }}</div>
        <div class="stat-label">本月总接单</div>
      </el-card>
      <el-card class="stat-card" shadow="never">
        <div class="stat-value success">{{ stats?.completedOrders ?? '--' }}</div>
        <div class="stat-label">本月已完成</div>
      </el-card>
      <el-card class="stat-card" shadow="never">
        <div class="stat-value warning">{{ stats?.pendingOrders ?? '--' }}</div>
        <div class="stat-label">待确认订单</div>
      </el-card>
      <el-card class="stat-card" shadow="never">
        <div class="stat-value danger">{{ stats?.cancelledOrders ?? '--' }}</div>
        <div class="stat-label">本月已取消</div>
      </el-card>
      <el-card class="stat-card" shadow="never">
        <div class="stat-value primary">{{ stats?.avgScore?.toFixed(1) ?? '--' }}</div>
        <div class="stat-label">综合评分</div>
        <el-rate :model-value="stats?.avgScore ?? 0" disabled size="small" style="margin-top:4px" />
      </el-card>
      <el-card class="stat-card" shadow="never">
        <div class="stat-value">{{ stats?.reviewCount ?? '--' }}</div>
        <div class="stat-label">累计评价数</div>
      </el-card>
    </div>

    <!-- ── 抢档期队列管理 ── -->
    <el-card class="queue-section" style="margin-top: 24px">
      <template #header>
        <span style="font-size:16px;font-weight:600">抢档期排队管理</span>
        <el-select v-model="selectedScheduleId" placeholder="选择档期" style="margin-left:16px;width:220px"
          @change="fetchQueue" clearable>
          <el-option v-for="s in rushSchedules" :key="s.id"
            :label="`${s.date} ${s.timeSlot || '全天'}（${s.currentQueueSize}人排队）`"
            :value="s.id" />
        </el-select>
        <el-button style="margin-left:8px" :icon="Refresh" circle @click="fetchRushSchedules" />
      </template>

      <div v-if="!selectedScheduleId" class="queue-empty">
        <el-empty description="请先选择一个抢档期档期" :image-size="80" />
      </div>

      <div v-else v-loading="queueLoading">
        <el-empty v-if="queue.length === 0 && !queueLoading" description="该档期暂无排队记录" :image-size="80" />
        <el-table v-else :data="queue" stripe>
          <el-table-column label="名次" width="70" align="center">
            <template #default="{ row }">
              <span class="rank-badge">{{ row.rankNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="客人" prop="userNickname" />
          <el-table-column label="抢档时间" width="160">
            <template #default="{ row }">{{ row.rushTime?.slice(0, 16) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="160">
            <template #default="{ row }">
              <el-select v-model="row.status" size="small"
                @change="(val: number) => updateStatus(row.id, val)">
                <el-option v-for="(s, k) in RUSH_STATUS_MAP" :key="k"
                  :label="s.label" :value="Number(k)" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <!-- ── 快捷入口 ── -->
    <div class="shortcut-grid" style="margin-top: 24px">
      <el-card class="shortcut-card" @click="$router.push('/orders/received')" shadow="hover">
        <el-icon size="32" color="#409eff"><List /></el-icon>
        <div>查看预约订单</div>
      </el-card>
      <el-card class="shortcut-card" @click="$router.push('/schedule/manage')" shadow="hover">
        <el-icon size="32" color="#67c23a"><Calendar /></el-icon>
        <div>管理档期</div>
      </el-card>
      <el-card class="shortcut-card" @click="$router.push('/merchant/profile')" shadow="hover">
        <el-icon size="32" color="#e6a23c"><Edit /></el-icon>
        <div>编辑资料</div>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Refresh, List, Calendar, Edit } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { merchantApi, scheduleApi } from '@/api'
import { RUSH_STATUS_MAP, type MerchantStatsVO, type ScheduleVO, type RushRecordVO } from '@/types'

// ── 统计数据 ──────────────────────────────────────────────
const stats = ref<MerchantStatsVO | null>(null)
const statsLoading = ref(false)

async function fetchStats() {
  statsLoading.value = true
  try {
    stats.value = await merchantApi.getStats()
  } finally {
    statsLoading.value = false
  }
}

// ── 抢档期管理 ────────────────────────────────────────────
const rushSchedules = ref<ScheduleVO[]>([])
const selectedScheduleId = ref<number | null>(null)
const queue = ref<RushRecordVO[]>([])
const queueLoading = ref(false)
const myMerchantId = ref<number | null>(null)

async function fetchRushSchedules() {
  const now = new Date()
  const month = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
  try {
    if (!myMerchantId.value) {
      const info = await merchantApi.getMyInfo()
      myMerchantId.value = info.id
    }
    const all = await scheduleApi.listByMonth(myMerchantId.value, month)
    rushSchedules.value = all.filter(s => s.bookType === 1)
  } catch { /* ignore */ }
}

async function fetchQueue() {
  if (!selectedScheduleId.value) return
  queueLoading.value = true
  try {
    queue.value = await scheduleApi.getQueue(selectedScheduleId.value)
  } finally {
    queueLoading.value = false
  }
}

async function updateStatus(rushId: number, status: number) {
  try {
    await scheduleApi.updateRushStatus(rushId, status)
    ElMessage.success('状态已更新')
  } catch { /* axios 拦截器已处理 */ }
}

onMounted(async () => {
  await Promise.all([fetchStats(), fetchRushSchedules()])
})
</script>

<style scoped>
.page-title { font-size: 20px; font-weight: 600; margin-bottom: 20px; }

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
}

.stat-card { text-align: center; padding: 8px 0; }
.stat-value { font-size: 32px; font-weight: 700; color: #303133; }
.stat-value.success { color: #67c23a; }
.stat-value.warning { color: #e6a23c; }
.stat-value.danger  { color: #f56c6c; }
.stat-value.primary { color: #409eff; }
.stat-label { font-size: 13px; color: #909399; margin-top: 4px; }

.queue-empty { padding: 40px 0; }

.rank-badge {
  display: inline-flex; align-items: center; justify-content: center;
  width: 28px; height: 28px; border-radius: 50%;
  background: #409eff; color: #fff; font-size: 13px; font-weight: 600;
}

.shortcut-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.shortcut-card {
  text-align: center;
  padding: 16px 0;
  cursor: pointer;
  transition: transform 0.15s;
}
.shortcut-card:hover { transform: translateY(-2px); }
.shortcut-card .el-icon { display: block; margin: 0 auto 8px; }
.shortcut-card div { font-size: 14px; color: #606266; }
</style>
