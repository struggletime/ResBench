import { Alert, Checkbox, Collapse, Input, Modal, Space, Tag, Tooltip } from 'antd';
import { useMemo, useState } from 'react';

const MetricSelector = ({
  open,
  catalog,
  selected,
  onCancel,
  onConfirm,
  maxMetrics,
  containerExtraIds = [],
}) => {
  const [draft, setDraft] = useState(selected);
  const [search, setSearch] = useState('');
  const [formError, setFormError] = useState('');

  // 容器测试运行时前端还会自动追加这些容器指标（CONTAINER_DEFAULT_METRIC_IDS），
  // manager 的 max-metrics 校验会把它们一起数进去。所以这里必须按「实际会发给 manager 的
  // 指标集合」判断上限，不能只看用户勾了多少 —— 只勾 21 个、跑容器测试时会变成 23 个被拒，
  // 会话建不起来，曲线和归档全是空的，页面上却只显示「部分缺失」。
  const effectiveIds = useMemo(
    () => Array.from(new Set([...draft, ...containerExtraIds])),
    [containerExtraIds, draft],
  );
  const limit = Number(maxMetrics) > 0 ? Number(maxMetrics) : 0;
  const overLimit = limit > 0 && effectiveIds.length > limit;
  const reserved = containerExtraIds.filter((metricId) => !draft.includes(metricId)).length;

  const categories = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    if (!keyword) return catalog;
    return (catalog || []).map((category) => ({
      ...category,
      subcategories: (category.subcategories || []).map((subcategory) => ({
        ...subcategory,
        metrics: (subcategory.metrics || []).filter((metric) =>
          `${metric.name} ${metric.metricId} ${category.name} ${subcategory.name}`.toLowerCase().includes(keyword)),
      })).filter((subcategory) => subcategory.metrics.length > 0),
    })).filter((category) => category.subcategories.length > 0);
  }, [catalog, search]);

  const toggle = (metricId, checked) => {
    setFormError('');
    setDraft((current) => checked
      ? Array.from(new Set([...current, metricId]))
      : current.filter((id) => id !== metricId));
  };

  const handleOk = () => {
    if (overLimit) {
      setFormError(`实际会采集 ${effectiveIds.length} 个指标，超过 manager 上限 ${limit} 个`
        + `（含运行时自动追加的 ${reserved} 个容器指标）。请再取消勾选 ${effectiveIds.length - limit} 个。`);
      return;
    }
    setFormError('');
    onConfirm(draft);
  };

  return (
    <Modal
      title="选择监控指标"
      open={open}
      width={720}
      onCancel={onCancel}
      onOk={handleOk}
      okText="确定"
      cancelText="取消"
      destroyOnHidden={false}
    >
      <div style={{ marginBottom: 12, color: overLimit ? '#cf1322' : '#5f6672' }}>
        已选 {draft.length} 个
        {reserved > 0 ? `，运行时另自动追加 ${reserved} 个容器指标` : ''}
        {limit > 0 ? ` / 上限 ${limit} 个` : ''}
      </div>
      {formError ? (
        <Alert type="error" showIcon style={{ marginBottom: 12 }} message={formError} />
      ) : overLimit ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={`已超过 manager 上限 ${limit} 个，确定前需要先取消勾选。`}
        />
      ) : null}
      <Input.Search
        allowClear
        placeholder="搜索中文名、英文名或 metricId"
        value={search}
        onChange={(event) => setSearch(event.target.value)}
        style={{ marginBottom: 12 }}
      />
      <Collapse
        items={categories.map((category) => ({
          key: category.id,
          label: category.name,
          children: (
            <Collapse
              ghost
              items={(category.subcategories || []).map((subcategory) => ({
                key: `${category.id}-${subcategory.id}`,
                label: subcategory.name,
                children: (
                  <Space direction="vertical" style={{ width: '100%' }}>
                    {(subcategory.metrics || []).map((metric) => (
                      <Tooltip key={metric.metricId} title={metric.available ? metric.description : metric.unavailableReason}>
                        <Checkbox
                          checked={draft.includes(metric.metricId)}
                          disabled={!metric.available}
                          onChange={(event) => toggle(metric.metricId, event.target.checked)}
                        >
                          {metric.name} <Tag>{metric.unit}</Tag>
                        </Checkbox>
                      </Tooltip>
                    ))}
                  </Space>
                ),
              }))}
            />
          ),
        }))}
      />
    </Modal>
  );
};

export default MetricSelector;
