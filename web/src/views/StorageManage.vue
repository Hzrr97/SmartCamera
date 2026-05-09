<template>
  <div class="storage-manage">
    <!-- Stats Cards -->
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-label">已用存储</div>
            <div class="stat-value">{{ formatBytes(stats.totalUsedBytes || 0) }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-label">视频分片总数</div>
            <div class="stat-value">{{ stats.totalSegments || 0 }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-label">保留天数</div>
            <div class="stat-value">{{ stats.retentionDays || 30 }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Storage Trend Chart -->
    <el-card style="margin-top: 20px;">
      <template #header>存储用量趋势 (30天)</template>
      <div ref="chartRef" style="height: 300px;"></div>
    </el-card>

    <!-- Settings -->
    <el-card style="margin-top: 20px;">
      <template #header>存储设置</template>
      <div class="settings-row">
        <span>视频保留天数</span>
        <el-input-number
          v-model="retentionDays"
          :min="1"
          :max="365"
          style="width: 140px;"
        />
        <el-button type="primary" @click="saveRetentionDays">保存设置</el-button>
      </div>
      <div class="settings-row" style="margin-top: 12px;">
        <el-popconfirm title="确认清理过期视频？" @confirm="handleCleanup">
          <template #reference>
            <el-button type="danger">立即清理过期视频</el-button>
          </template>
        </el-popconfirm>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getStorageStats, updateRetentionDays, deleteExpiredSegments } from '@/api/system'
import { formatBytes } from '@/utils/format'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'

const stats = ref({})
const retentionDays = ref(30)
const chartRef = ref(null)
let chart = null

onMounted(async () => {
  await fetchStats()
  initChart()
})

async function fetchStats() {
  try {
    stats.value = await getStorageStats()
    retentionDays.value = stats.value.retentionDays || 30
  } catch (e) {
    ElMessage.error('加载存储统计失败')
  }
}

function initChart() {
  if (!chartRef.value) return
  chart = echarts.init(chartRef.value)

  // Mock data - in production, fetch daily stats from API
  const days = Array.from({ length: 30 }, (_, i) => `${i + 1}日`)
  const data = Array.from({ length: 30 }, (_, i) => 20 + i * 0.8 + Math.random() * 2)

  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: '10%', right: '5%', bottom: '10%', top: '10%' },
    xAxis: { type: 'category', data: days },
    yAxis: { type: 'value', name: 'GB' },
    series: [{
      data: data,
      type: 'line',
      smooth: true,
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(64, 158, 255, 0.5)' },
          { offset: 1, color: 'rgba(64, 158, 255, 0.05)' }
        ])
      },
      itemStyle: { color: '#409EFF' }
    }]
  })
}

async function saveRetentionDays() {
  try {
    await updateRetentionDays(retentionDays.value)
    ElMessage.success('设置已保存')
  } catch (e) {
    ElMessage.error('保存失败')
  }
}

async function handleCleanup() {
  try {
    await deleteExpiredSegments()
    ElMessage.success('过期视频清理完成')
    fetchStats()
  } catch (e) {
    ElMessage.error('清理失败')
  }
}
</script>

<style scoped>
.stat-item {
  text-align: center;
  padding: 10px 0;
}

.stat-label {
  font-size: 14px;
  color: #909399;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: #303133;
}

.settings-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.settings-row span {
  font-size: 14px;
  color: #606266;
}
</style>
