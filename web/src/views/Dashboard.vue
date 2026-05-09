<template>
  <div class="dashboard">
    <!-- Stats Cards -->
    <el-row :gutter="16" class="stats-row">
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <div class="stat-icon" style="background: #409EFF;">
              <el-icon :size="28"><VideoCamera /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ status.totalCameras || 0 }}</div>
              <div class="stat-label">摄像头总数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <div class="stat-icon" style="background: #67C23A;">
              <el-icon :size="28"><CircleCheckFilled /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ status.onlineCameras || 0 }}</div>
              <div class="stat-label">在线摄像头</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <div class="stat-icon" style="background: #E6A23C;">
              <el-icon :size="28"><VideoPlay /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ status.recordingCount || 0 }}</div>
              <div class="stat-label">录制中</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <div class="stat-icon" style="background: #909399;">
              <el-icon :size="28"><Files /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ formatBytes(status.storageUsedBytes || 0) }}</div>
              <div class="stat-label">存储用量</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 20px;">
      <!-- Camera Status List -->
      <el-col :span="14">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>摄像头状态</span>
              <el-button type="primary" size="small" @click="$router.push('/monitor')">
                快速跳转实时监控
              </el-button>
            </div>
          </template>
          <el-table :data="cameras" v-loading="loading">
            <el-table-column prop="cameraName" label="名称" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ONLINE' ? 'success' : 'danger'" size="small">
                  {{ row.status === 'ONLINE' ? '在线' : '离线' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="resolution" label="分辨率" width="120" />
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button size="small" @click="toggleStream(row)">
                  {{ row.status === 'ONLINE' ? '停止' : '启动' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <!-- Storage Chart -->
      <el-col :span="10">
        <el-card>
          <template #header>存储用量趋势 (7天)</template>
          <div ref="chartRef" style="height: 300px;"></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { listCameras, startStream, stopStream } from '@/api/camera'
import { getSystemStatus } from '@/api/system'
import { formatBytes } from '@/utils/format'
import * as echarts from 'echarts'

const status = ref({})
const cameras = ref([])
const loading = ref(true)
const chartRef = ref(null)
let chart = null
let timer = null

onMounted(() => {
  fetchData()
  initChart()
  timer = setInterval(fetchData, 30000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  if (chart) chart.dispose()
})

async function fetchData() {
  try {
    status.value = await getSystemStatus()
    cameras.value = await listCameras()
  } catch (e) {
    console.error('Failed to fetch dashboard data:', e)
  } finally {
    loading.value = false
  }
}

function initChart() {
  if (!chartRef.value) return
  chart = echarts.init(chartRef.value)

  // Mock data - in production, fetch from API
  const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
  const data = [32.1, 35.4, 38.2, 40.5, 42.8, 44.1, 45.2]

  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: '10%', right: '5%', bottom: '10%', top: '10%' },
    xAxis: { type: 'category', data: days },
    yAxis: { type: 'value', name: 'GB' },
    series: [{
      data: data,
      type: 'line',
      smooth: true,
      areaStyle: { opacity: 0.3 },
      itemStyle: { color: '#409EFF' }
    }]
  })
}

async function toggleStream(row) {
  try {
    if (row.status === 'ONLINE') {
      await stopStream(row.id)
    } else {
      await startStream(row.id)
    }
    fetchData()
  } catch (e) {
    console.error('Failed to toggle stream:', e)
  }
}
</script>

<style scoped>
.stats-row .el-card {
  border-radius: 8px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stat-icon {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.stat-info {
  flex: 1;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
