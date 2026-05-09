import request from '@/utils/request'

export function listCameras() {
  return request.get('/api/v1/cameras')
}

export function createCamera(data) {
  return request.post('/api/v1/cameras', data)
}

export function updateCamera(id, data) {
  return request.put(`/api/v1/cameras/${id}`, data)
}

export function deleteCamera(id) {
  return request.delete(`/api/v1/cameras/${id}`)
}

export function startStream(id) {
  return request.post(`/api/v1/cameras/${id}/start`)
}

export function stopStream(id) {
  return request.post(`/api/v1/cameras/${id}/stop`)
}

export function getCameraStatus(id) {
  return request.get(`/api/v1/cameras/${id}/status`)
}
