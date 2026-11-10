import { request } from './client';

/** 容器测试默认追加的指标：容器 CPU 使用率、内存使用率。 */
export const CONTAINER_DEFAULT_METRIC_IDS = [
  'container.cpu.usage',
  'container.memory.usage',
];

export function fetchMonitoringCapabilities() {
  return request('/monitoring/capabilities');
}

export function fetchMonitoringCatalog(dbType = 'opengauss') {
  return request('/monitoring/catalog', { params: { dbType } });
}

export function discoverMonitoringTargets(body = {}) {
  return request('/monitoring/targets/discover', { method: 'POST', body });
}

export function createMonitoringSession(body) {
  return request('/monitoring/sessions', { method: 'POST', body });
}

export function fetchMonitoringSession(runId) {
  return request(`/monitoring/sessions/${encodeURIComponent(runId)}`);
}

export function completeMonitoringSession(runId) {
  return request(`/monitoring/sessions/${encodeURIComponent(runId)}/complete`, { method: 'POST' });
}

export function fetchMonitoringSeries(runId, params = {}) {
  return request(`/monitoring/sessions/${encodeURIComponent(runId)}/series`, { params });
}

export function fetchMonitoringHistory() {
  return request('/monitoring/history');
}

export function fetchMonitoringHistorySeries(runId) {
  return request(`/monitoring/history/${encodeURIComponent(runId)}/series`);
}

export function deleteMonitoringHistory(runId) {
  return request(`/monitoring/history/${encodeURIComponent(runId)}`, { method: 'DELETE' });
}
