<template>
  <div class="video-player-container">
    <video ref="videoRef" class="video-element" controls autoplay muted />
    <div v-if="error" class="video-error">{{ error }}</div>
    <div v-if="loading" class="video-loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>加载中...</span>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import mpegts from 'mpegts.js'

const props = defineProps({
  src: { type: String, required: true },
  autoplay: { type: Boolean, default: true }
})

const emit = defineEmits(['error', 'loaded'])

const videoRef = ref(null)
const error = ref('')
const loading = ref(true)
let player = null

onMounted(() => {
  initPlayer()
})

onUnmounted(() => {
  destroyPlayer()
})

watch(() => props.src, () => {
  destroyPlayer()
  initPlayer()
})

function initPlayer() {
  if (!mpegts.isSupported()) {
    error.value = '当前浏览器不支持 FLV 播放'
    loading.value = false
    emit('error', error.value)
    return
  }

  loading.value = true
  error.value = ''

  console.log('[VideoPlayer] Initializing player, src:', props.src)
  console.log('[VideoPlayer] mpegts.isSupported:', mpegts.isSupported())

  try {
    player = mpegts.createPlayer({
      type: 'flv',
      url: props.src,
      isLive: true,
      hasAudio: false
    }, {
      enableWorker: false,
      enableStashBuffer: false,
      stashInitialSize: 128,
      lazyLoad: false,
      liveSyncDurationCount: 1,
      liveBufferLatencyChasing: false,
      liveMaxLatencyDurationCount: 6
    })

    // Log video element events
    const video = videoRef.value
    video.addEventListener('error', (e) => {
      console.error('[VideoPlayer] video element error:', video.error)
    })
    video.addEventListener('loadeddata', () => console.log('[VideoPlayer] loadeddata'))
    video.addEventListener('loadedmetadata', () => console.log('[VideoPlayer] loadedmetadata'))
    video.addEventListener('playing', () => console.log('[VideoPlayer] playing'))
    video.addEventListener('waiting', () => console.log('[VideoPlayer] waiting'))
    video.addEventListener('canplay', () => console.log('[VideoPlayer] canplay'))

    player.on(mpegts.Events.MEDIA_INFO, (info) => {
      console.log('[VideoPlayer] MEDIA_INFO:', JSON.stringify(info))
      loading.value = false
      emit('loaded')
    })

    player.on(mpegts.Events.MEDIA_SEGMENT, () => {
      console.log('[VideoPlayer] MEDIA_SEGMENT')
      if (loading.value) {
        loading.value = false
        emit('loaded')
      }
    })

    player.on(mpegts.Events.LOADING_COMPLETE, () => {
      console.log('[VideoPlayer] LOADING_COMPLETE')
      loading.value = false
      emit('loaded')
    })

    player.on(mpegts.Events.ERROR, (errType, errDetail) => {
      console.error('[VideoPlayer] ERROR:', errType, errDetail)
      error.value = `播放错误: ${errType} - ${errDetail}`
      loading.value = false
      emit('error', error.value)
    })

    player.on(mpegts.Events.RECOVERED_ERROR, (errType, errDetail) => {
      console.warn('[VideoPlayer] recovered from error:', errType, errDetail)
    })

    // Attach media and load
    player.attachMediaElement(videoRef.value)
    console.log('[VideoPlayer] Calling load()...')
    player.load()

    if (props.autoplay) {
      player.play().catch(e => {
        console.warn('[VideoPlayer] 自动播放被阻止:', e.message)
      })
    }
  } catch (e) {
    error.value = '播放器初始化失败: ' + e.message
    loading.value = false
    emit('error', error.value)
  }
}

function destroyPlayer() {
  if (player) {
    player.pause()
    player.unload()
    player.detachMediaElement()
    player.destroy()
    player = null
  }
}
</script>

<style scoped>
.video-player-container {
  position: relative;
  width: 100%;
  background: #000;
  border-radius: 8px;
  overflow: hidden;
}

.video-element {
  width: 100%;
  height: 100%;
  display: block;
}

.video-error {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  color: #f56c6c;
  background: rgba(0, 0, 0, 0.8);
  padding: 12px 20px;
  border-radius: 4px;
  text-align: center;
}

.video-loading {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  color: #fff;
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
