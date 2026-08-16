import React, { useState, useEffect, useCallback } from 'react';
import { Card, Button, Typography, Space, message } from 'antd';
import { DownloadOutlined, ReloadOutlined, ClearOutlined } from '@ant-design/icons';
import { fetchReport, fetchMetricsSummary, clearCache } from '../api';
import type { OpsReport } from '../types';

const MetricsPanel: React.FC = () => {
  const [metrics, setMetrics] = useState<OpsReport>({
    totalRequests: 0,
    p50LatencyMs: 0,
    p95LatencyMs: 0,
    missP50LatencyMs: 0,
    missP95LatencyMs: 0,
    totalTokens: 0,
    cacheHitRate: 0,
    refusalRate: 0,
    answerComplianceRate: 0,
  });

  const refresh = useCallback(async () => {
    try {
      setMetrics(await fetchMetricsSummary());
    } catch {
      // keep last known values on transient failure
    }
  }, []);

  useEffect(() => {
    refresh();
    const interval = setInterval(refresh, 5000);
    return () => clearInterval(interval);
  }, [refresh]);

  const handleDownload = async () => {
    try {
      const blob = await fetchReport();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'operations_report.csv';
      a.click();
      URL.revokeObjectURL(url);
      message.success('报告下载成功');
    } catch {
      message.error('下载失败，请稍后重试');
    }
  };

  const handleClearCache = async () => {
    try {
      await clearCache();
      message.success('缓存已清空');
    } catch {
      message.error('清空缓存失败');
    }
  };

  const stats: { label: string; value: string; suffix?: string }[] = [
    { label: '请求数', value: String(metrics.totalRequests) },
    { label: 'Token 用量', value: String(metrics.totalTokens) },
    { label: 'P50', value: String(metrics.p50LatencyMs), suffix: 'ms' },
    { label: 'P95', value: String(metrics.p95LatencyMs), suffix: 'ms' },
    { label: 'P50 缓存未命中', value: String(metrics.missP50LatencyMs), suffix: 'ms' },
    { label: 'P95 缓存未命中', value: String(metrics.missP95LatencyMs), suffix: 'ms' },
    { label: '缓存命中率', value: metrics.cacheHitRate.toFixed(1), suffix: '%' },
    { label: '拒答率', value: metrics.refusalRate.toFixed(1), suffix: '%' },
    { label: '合规率', value: metrics.answerComplianceRate.toFixed(1), suffix: '%' },
  ];

  return (
    <Card size="small">
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
        <Typography.Title level={5} style={{ margin: 0, flex: 1 }}>运维指标</Typography.Title>
        <Space size={4}>
          <Button size="small" type="text" icon={<ReloadOutlined />} onClick={refresh}>刷新</Button>
          <Button size="small" type="text" icon={<DownloadOutlined />} onClick={handleDownload}>下载 CSV</Button>
          <Button size="small" type="text" icon={<ClearOutlined />} onClick={handleClearCache}>清空缓存</Button>
        </Space>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px 12px' }}>
        {stats.map(s => (
          <div key={s.label} style={{ background: '#fafafa', borderRadius: 6, padding: '8px 10px' }}>
            <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 2 }}>{s.label}</div>
            <div style={{ fontSize: 17, fontWeight: 600, color: '#262626', lineHeight: 1.2 }}>
              {s.value}
              {s.suffix && <span style={{ fontSize: 12, fontWeight: 400, color: '#8c8c8c', marginLeft: 2 }}>{s.suffix}</span>}
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
};

export default MetricsPanel;
