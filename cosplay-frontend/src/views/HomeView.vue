<template>
  <div class="page-container">
    <!-- 搜索栏 -->
    <el-card class="search-card">
      <div class="search-row">
        <el-input v-model="keyword" placeholder="搜索妆娘/摄影/商家名称" clearable style="width: 280px" />
        <el-select v-model="city" placeholder="城市" clearable style="width: 140px">
          <el-option v-for="c in CITIES" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-model="serviceType" placeholder="服务类型" clearable style="width: 140px">
          <el-option v-for="(label, val) in SERVICE_TYPE_MAP" :key="val" :label="label" :value="Number(val)" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
      </div>
    </el-card>

    <!-- 商家列表 -->
    <div v-if="loading" class="loading-wrap">
      <el-skeleton :rows="3" animated />
    </div>

    <div v-else-if="merchants.length === 0" class="empty-wrap">
      <el-empty description="暂无符合条件的商家" />
    </div>

    <div v-else class="merchant-grid">
      <div v-for="m in merchants" :key="m.id" class="merchant-card" @click="goDetail(m.id)">
        <el-avatar :size="64" :src="m.avatar" class="avatar" />
        <div class="info">
          <div class="name">{{ m.nickname }}</div>
          <div class="meta">
            <el-icon><Location /></el-icon>{{ m.city || '未填写' }}
            <el-rate :model-value="m.avgScore" disabled text-color="#ff9900"
              score-template="{value}" show-score style="margin-left: 12px; height: auto" />
            <span class="review-count">（{{ m.reviewCount }}评）</span>
          </div>
          <div class="tags">
            <el-tag v-for="type in m.serviceTypes" :key="type" size="small" type="success" style="margin: 2px">
              {{ SERVICE_TYPE_MAP[type] }}
            </el-tag>
          </div>
          <div class="intro">{{ m.intro || '暂无简介' }}</div>
        </div>
      </div>
    </div>

    <!-- 页码分页（搜索结果用页码，不用游标） -->
    <el-pagination v-if="total > 0" background layout="prev, pager, next" :total="total"
      :page-size="pageSize" v-model:current-page="currentPage" @current-change="fetchMerchants"
      style="margin-top: 24px; justify-content: center; display: flex" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search, Location } from '@element-plus/icons-vue'
import { merchantApi } from '@/api'
import { SERVICE_TYPE_MAP, type MerchantVO } from '@/types'

const router = useRouter()

const keyword = ref('')
const city = ref('')
const serviceType = ref<number | ''>('')
const merchants = ref<MerchantVO[]>([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = 10
const total = ref(0)

// 常用城市列表（实际项目可从后端动态加载）
const CITIES = ['北京', '上海', '广州', '深圳', '成都', '杭州', '武汉', '南京', '西安', '重庆']

async function fetchMerchants() {
  loading.value = true
  try {
    const data = await merchantApi.search({
      keyword: keyword.value || undefined,
      city: city.value || undefined,
      serviceType: (serviceType.value as number) || undefined,
      page: currentPage.value,
      size: pageSize
    })
    merchants.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 1
  fetchMerchants()
}

function goDetail(id: number) {
  router.push(`/merchant/${id}`)
}

onMounted(fetchMerchants)
</script>

<style scoped>
.search-card {
  margin-bottom: 24px;
}

.search-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: center;
}

.merchant-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}

.merchant-card {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  display: flex;
  gap: 16px;
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
  border: 1px solid #e4e7ed;
}

.merchant-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
  transform: translateY(-2px);
}

.info {
  flex: 1;
  overflow: hidden;
}

.name {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 6px;
}

.meta {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #606266;
  margin-bottom: 8px;
}

.review-count {
  color: #909399;
  font-size: 12px;
}

.tags {
  margin-bottom: 8px;
}

.intro {
  font-size: 13px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.loading-wrap,
.empty-wrap {
  padding: 40px 0;
}
</style>
