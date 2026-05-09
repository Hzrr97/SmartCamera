import request from '@/utils/request'

export function querySegments(cameraId, startTime, endTime) {
  return request.get('/api/v1/playback/segments', {
    params: { cameraId, startTime, endTime }
  })
}

export function getMergeUrl(cameraId, startTime, endTime) {
  return request.get('/api/v1/playback/merge', {
    params: { cameraId, startTime, endTime }
  })
}

export function getPlaylist(cameraId, startTime, endTime) {
  return request.get('/api/v1/playback/playlist', {
    params: { cameraId, startTime, endTime }
  })
}

export function downloadSegment(segmentId) {
  return request.get(`/api/v1/playback/download/${segmentId}`)
}
