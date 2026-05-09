import request from '@/utils/request'

export function getSystemStatus() {
  return request.get('/api/v1/system/status')
}

export function getStorageStats() {
  return request.get('/api/v1/storage/stats')
}

export function deleteExpiredSegments() {
  return request.delete('/api/v1/storage/expired')
}

export function updateRetentionDays(days) {
  return request.put('/api/v1/storage/retention-days', { days })
}
