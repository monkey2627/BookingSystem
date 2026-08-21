<template>
  <div class="page-container">
    <h2 class="page-title">我的关注</h2>

    <div v-loading="loading">
      <el-empty v-if="merchants.length === 0 && !loading" description="还没有关注任何商家" />

      <div class="merchant-grid">
        <el-card
          v-for="m in merchants" :key="m.id"
          class="merchant-card"
          shadow="hover"
          @click="router.push(`/merchant/${m.id}`)">
          <div class="card-header">
            <el-avatar :size="56" :src="m.avatar" />
            <div class="card-meta">
              <div class="card-name">{{ m.nickname }}</div>
              <el-rate :model-value="m.avgScore" disabled size="small" show-score
                score-template="{value}" text-color="#ff9900" />
              <div class="card-reviews">{{ m.reviewCount }} 条评价</div>
            </div>
          </div>

          <div class="card-tags">
            <el-tag v-for="t in m.serviceTypes" :key="t" size="small" type="success" style="margin: 2px">
              {{ SERVICE_TYPE_MAP[t] }}
            </el-tag>
            <el-tag v-if="m.city" size="small" type="info" style="margin: 2px">{{ m.city }}</el-tag>
          </div>

          <p class="card-intro">{{ m.intro || '该商家暂未填写简介' }}</p>

          <div class="card-footer">
            <el-button size="small" type="danger" plain
              @click.stop="handleUnfollow(m)">
              取消关注
            </el-button>
            <el-button size="small" type="primary" plain
              @click.stop="router.push(`/merchant/${m.id}`)">
              查看主页
            </el-button>
          </div>
        </el-card>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { followApi } from '@/api'
import { SERVICE_TYPE_MAP, type MerchantVO } from '@/types'

const router = useRouter()
const loading = ref(false)
const merchants = ref<MerchantVO[]>([])

async function fetchFollows() {
  loading.value = true
  try {
    merchants.value = await followApi.myFollows()
  } finally {
    loading.value = false
  }
}

async function handleUnfollow(m: MerchantVO) {
  await ElMessageBox.confirm(`确定取消关注「${m.nickname}」吗？`, '提示', { type: 'warning' })
  await followApi.unfollow(m.id)
  merchants.value = merchants.value.filter(x => x.id !== m.id)
  ElMessage.success('已取消关注')
}

onMounted(fetchFollows)
</script>

<style scoped>
.page-title { font-size: 20px; font-weight: 600; margin-bottom: 20px; }

.merchant-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.merchant-card { cursor: pointer; transition: transform 0.15s; }
.merchant-card:hover { transform: translateY(-2px); }

.card-header { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 12px; }
.card-meta { flex: 1; min-width: 0; }
.card-name { font-size: 16px; font-weight: 600; margin-bottom: 4px; }
.card-reviews { font-size: 12px; color: #909399; margin-top: 2px; }

.card-tags { margin-bottom: 8px; }

.card-intro {
  font-size: 13px;
  color: #909399;
  line-height: 1.5;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  margin-bottom: 12px;
}

.card-footer { display: flex; justify-content: flex-end; gap: 8px; }
</style>
