<template>
  <div class="playback">
    <!-- Controls -->
    <div class="playback-controls">
      <el-select v-model="selectedCamera" placeholder="选择摄像头" style="width: 200px;">
        <el-option
          v-for="cam in cameras"
          :key="cam.id"
          :label="cam.cameraName"
          :value="cam.cameraId"
        />
      </el-select>
      <el-date-picker
        v-model="selectedDate"
        type="date"
        placeholder="选择日期"
        format="YYYY-MM-DD"
        value-format="YYYY-MM-DD"
        style="width: 160px;"
      />
      <el-button type="primary" @click="searchSegments">
        <el-icon><Search /></el-icon> 搜索
      </el-button>
    </div>

    <!-- Timeline -->
    <div class="timeline-container">
      <div class="timeline-header">
        <span>00:00</span>
        <span>06:00</span>
        <span>12:00</span>
        <span>18:00</span>
        <span>23:59</span>
      </div>
      <div
        class="timeline-bar"
        ref="timelineRef"
        @mousedown="onMouseDown"
        @mousemove="onMouseMove"
        @mouseleave="onMouseLeave"
      >
        <!-- 实时悬浮时间指示器 -->
        <div class="timeline-hover-indicator" v-if="hoverTime" :style="hoverIndicatorStyle">
          <div class="timeline-hover-time">{{ hoverTime }}</div>
        </div>

        <div
          v-for="(seg, idx) in segments"
          :key="idx"
          class="timeline-segment"
          :style="{
            left: seg.percentStart + '%',
            width: (seg.percentEnd - seg.percentStart) + '%'
          }"
          :title="formatTime(seg.startTime) + ' - ' + formatTime(seg.endTime)"
        />
        <div class="timeline-selection" v-if="timeRange" :style="selectionStyle">
          <!-- {{ timeRange.start }} ~ {{ timeRange.end }} -->
        </div>
        <div class="timeline-drag-preview" v-if="isDragging" :style="dragPreviewStyle">
          <!-- {{ dragPreviewStart }} ~ {{ dragPreviewEnd }} -->
        </div>
      </div>
    </div>

    <div class="playback-actions" v-if="timeRange">
      <span>选中: {{ timeRange.start }} ~ {{ timeRange.end }}</span>
      <el-button type="primary" @click="playSelected">
        <el-icon><VideoPlay /></el-icon> 播放
      </el-button>
      <el-button @click="downloadSelected">
        <el-icon><Download /></el-icon> 下载
      </el-button>
    </div>

    <!-- Video Player -->
    <div class="playback-player" v-if="playbackUrl">
      <video
        ref="videoRef"
        controls
        class="playback-video"
        :src="playbackUrl"
      />
    </div>

    <!-- Segment List -->
    <el-card v-if="segments.length > 0" style="margin-top: 20px;">
      <template #header>录像分片列表 ({{ segments.length }} 个)</template>
      <el-table :data="segments">
        <el-table-column prop="startTime" label="开始时间" width="200" />
        <el-table-column prop="endTime" label="结束时间" width="200" />
        <el-table-column label="时长" width="100">
          <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatBytes(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="操作">
          <template #default="{ row }">
            <el-button size="small" @click="playSegment(row)">播放</el-button>
            <el-button size="small" :href="row.downloadUrl" target="_blank">下载</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { listCameras } from '@/api/camera'
import { querySegments, getMergeUrl, getStreamUrl } from '@/api/playback'
import { formatBytes, formatDate } from '@/utils/format'
import { ElMessage } from 'element-plus'

const cameras = ref([])
const selectedCamera = ref('')
const selectedDate = ref(new Date().toISOString().split('T')[0])
const segments = ref([])
const playbackUrl = ref('')
const timeRange = ref(null)
const videoRef = ref(null)
const timelineRef = ref(null)
const isDragging = ref(false)
const dragStartPercent = ref(0)
const dragCurrentPercent = ref(0)
const hoverPercent = ref(0)
const hoverTime = ref('')

onMounted(async () => {
  try {
    cameras.value = await listCameras()
    if (cameras.value.length > 0) {
      selectedCamera.value = cameras.value[0].cameraId
    }
  } catch (e) {
    ElMessage.error('加载摄像头列表失败')
  }

  window.addEventListener('mouseup', onMouseUp)
})

onUnmounted(() => {
  window.removeEventListener('mouseup', onMouseUp)
})

async function searchSegments() {
  if (!selectedCamera.value) {
    ElMessage.warning('请先选择摄像头')
    return
  }

  const start = selectedDate.value + ' 00:00:00'
  const end = selectedDate.value + ' 23:59:59'

  try {
    segments.value = await querySegments(selectedCamera.value, start, end)
    ElMessage.success(`找到 ${segments.value.length} 个录像分片`)
  } catch (e) {
    ElMessage.error('查询失败: ' + e.message)
  }
}

function getPercentFromEvent(event) {
  const rect = timelineRef.value.getBoundingClientRect()
  const percent = Math.max(0, Math.min(100, ((event.clientX - rect.left) / rect.width) * 100))
  return percent
}

function percentToTimeStr(percent) {
  const totalSeconds = Math.floor((percent / 100) * 24 * 60 * 60)
  // 最大值限制为 23:59:59
  const clamped = Math.min(totalSeconds, 23 * 3600 + 59 * 60 + 59)
  const hours = Math.floor(clamped / 3600)
  const mins = Math.floor((clamped % 3600) / 60)
  const secs = clamped % 60
  return `${String(hours).padStart(2, '0')}:${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`
}

function onMouseDown(event) {
  const percent = getPercentFromEvent(event)
  isDragging.value = true
  dragStartPercent.value = percent
  dragCurrentPercent.value = percent
  timeRange.value = null // 立即清除上次选区
}

function onMouseMove(event) {
  const percent = getPercentFromEvent(event)
  hoverPercent.value = percent
  hoverTime.value = percentToTimeStr(percent)

  // 拖拽中实时更新选区
  if (isDragging.value) {
    dragCurrentPercent.value = percent
  }
}

function onMouseLeave() {
  hoverTime.value = ''
  hoverPercent.value = 0
}

function onMouseUp(event) {
  if (!isDragging.value) return
  isDragging.value = false
  const endPercent = getPercentFromEvent(event)

  const start = Math.min(dragStartPercent.value, endPercent)
  const end = Math.max(dragStartPercent.value, endPercent)
  if (end - start < 0.5) {
    timeRange.value = null
  } else {
    timeRange.value = {
      start: percentToTimeStr(start),
      end: percentToTimeStr(end)
    }
  }
}

const selectionStyle = computed(() => {
  if (!timeRange.value) return {}
  const startPercent = (parseTimeToMinutes(timeRange.value.start) / (24 * 60)) * 100
  const endPercent = (parseTimeToMinutes(timeRange.value.end) / (24 * 60)) * 100
  const left = Math.min(startPercent, endPercent)
  const width = Math.abs(endPercent - startPercent)
  return {
    left: left + '%',
    width: width + '%',
    background: 'rgba(64, 158, 255, 0.3)',
    borderRadius: '2px'
  }
})

const hoverIndicatorStyle = computed(() => {
  if (!hoverTime.value) return {}
  return {
    left: hoverPercent.value + '%'
  }
})

const dragPreviewStyle = computed(() => {
  if (!isDragging.value) return {}
  const start = Math.min(dragStartPercent.value, dragCurrentPercent.value)
  const width = Math.abs(dragCurrentPercent.value - dragStartPercent.value)
  return {
    left: start + '%',
    width: width + '%',
    background: 'rgba(64, 158, 255, 0.3)',
    borderRadius: '2px'
  }
})

const dragPreviewStart = computed(() => {
  if (!isDragging.value) return ''
  const start = Math.min(dragStartPercent.value, dragCurrentPercent.value)
  return percentToTimeStr(start)
})

const dragPreviewEnd = computed(() => {
  if (!isDragging.value) return ''
  const end = Math.max(dragStartPercent.value, dragCurrentPercent.value)
  return percentToTimeStr(end)
})

async function playSelected() {
  if (!timeRange.value || !selectedCamera.value) return

  const start = selectedDate.value + ' ' + timeRange.value.start
  const end = selectedDate.value + ' ' + timeRange.value.end

  try {
    ElMessage.info('正在加载回放...')
    const result = await getMergeUrl(selectedCamera.value, start, end)
    playbackUrl.value = result.playbackUrl
  } catch (e) {
    ElMessage.error('加载回放失败: ' + e.message)
  }
}

async function playSegment(row) {
  playbackUrl.value = getStreamUrl(row.id)
}

function downloadSelected() {
  if (!timeRange.value) return
  ElMessage.info('下载功能需要后端生成MP4文件')
}

function parseTimeToMinutes(timeStr) {
  const [h, m] = timeStr.split(':').map(Number)
  return h * 60 + m
}

function formatTime(timeStr) {
  return timeStr
}

function formatDuration(ms) {
  const seconds = Math.floor(ms / 1000)
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}
</script>

<style scoped>
.playback {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.playback-controls {
  display: flex;
  gap: 12px;
  align-items: center;
}

.timeline-container {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
}

.timeline-header {
  display: flex;
  justify-content: space-between;
  color: #909399;
  font-size: 12px;
  margin-bottom: 8px;
}

.timeline-bar {
  height: 32px;
  background: #ebeef5;
  border-radius: 4px;
  position: relative;
  cursor: crosshair;
  user-select: none;
}

.timeline-segment {
  position: absolute;
  height: 100%;
  background: #67C23A;
  border-radius: 2px;
  opacity: 0.8;
}

.timeline-segment:hover {
  opacity: 1;
}

.timeline-selection {
  position: absolute;
  top: 0;
  height: 100%;
  color: #409EFF;
  font-size: 11px;
  display: flex;
  align-items: center;
  padding-left: 6px;
  pointer-events: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.timeline-drag-preview {
  position: absolute;
  top: 0;
  height: 100%;
  color: #409EFF;
  font-size: 11px;
  display: flex;
  align-items: center;
  padding-left: 6px;
  pointer-events: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.timeline-hover-indicator {
  position: absolute;
  top: 0;
  height: 100%;
  pointer-events: none;
  display: flex;
  justify-content: center;
}

.timeline-hover-indicator::before {
  content: '';
  position: absolute;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 1px;
  height: 100%;
  background: #409EFF;
}

.timeline-hover-time {
  position: absolute;
  top: -24px;
  transform: translateX(-50%);
  background: rgba(0, 0, 0, 0.75);
  color: #fff;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 4px;
  white-space: nowrap;
}

.playback-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.playback-player {
  background: #000;
  border-radius: 8px;
  overflow: hidden;
}

.playback-video {
  width: 100%;
  max-height: 500px;
  display: block;
}
</style>
