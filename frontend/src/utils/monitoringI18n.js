// 监控目录（/monitoring/catalog）只回传中文 name；英文界面按 metricId / 子分类 id
// 查词典取英文名。词典缺条目时回退后端原文，不会露出 key。

export const MONITORING_CATEGORY_KEYS = {
  host: 'monitoringPage.category.host',
  database: 'monitoringPage.category.database',
  container: 'monitoringPage.category.container',
  ebpf: 'monitoringPage.category.ebpf',
};

export function monitoringCategoryName(category, translate) {
  const id = typeof category === 'string' ? category : category?.id;
  const fallback = typeof category === 'string' ? category : (category?.name || category?.id || '');
  const key = MONITORING_CATEGORY_KEYS[id];
  if (key) {
    const translated = translate(key);
    if (translated !== key) return translated;
  }
  return fallback;
}

export function monitoringSubcategoryName(subcategory, translate) {
  if (!subcategory) return '';
  const key = `monitoring.subcategory.${subcategory.id}`;
  const translated = translate(key);
  return translated !== key ? translated : (subcategory.name || subcategory.id || '');
}

export function monitoringMetricName(metric, translate) {
  if (!metric) return '';
  const key = `monitoring.metricName.${metric.metricId}`;
  const translated = translate(key);
  return translated !== key ? translated : (metric.name || metric.metricId || '');
}

export function monitoringMetricDescription(metric, translate) {
  if (!metric) return '';
  const key = `monitoring.metricDesc.${metric.metricId}`;
  const translated = translate(key);
  return translated !== key ? translated : (metric.description || '');
}

export default monitoringMetricName;
