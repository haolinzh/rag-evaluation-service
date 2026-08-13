import React, { useState, useEffect, useCallback } from 'react';
import { Card, Statistic, Row, Col, Button, Typography, message } from 'antd';
import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { fetchReport, fetchMetricsSummary } from '../api';
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
  });

  const refresh = useCallback(async () => {
    try {
      const data = await fetchMetricsSummary();
      setMetrics(data);
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

  return (
    <div>
      <Typography.Title level={5} style={{ marginTop: 0 }}>运维指标</Typography.Title>

      <Row gutter={[12, 12]}>
        <Col span={12}>
          <Card size="small">
            <Statistic title="P50 延迟（总）" value={metrics.p50LatencyMs} suffix="ms" precision={0} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <Statistic title="P95 延迟（总）" value={metrics.p95LatencyMs} suffix="ms" precision={0} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <Statistic title="P50 延迟（未命中缓存）" value={metrics.missP50LatencyMs} suffix="ms" precision={0} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <Statistic title="P95 延迟（未命中缓存）" value={metrics.missP95LatencyMs} suffix="ms" precision={0} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <Statistic title="请求数" value={metrics.totalRequests} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <Statistic title="Token 用量" value={metrics.totalTokens} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <Statistic title="缓存命中率" value={metrics.cacheHitRate} suffix="%" precision={1} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <Statistic title="拒答率" value={metrics.refusalRate} suffix="%" precision={1} />
          </Card>
        </Col>
      </Row>

      <div style={{ marginTop: 16 }}>
        <Button icon={<ReloadOutlined />} size="small" style={{ marginRight: 8 }} onClick={refresh}>
          刷新
        </Button>
        <Button type="primary" icon={<DownloadOutlined />} onClick={handleDownload} size="small">
          下载 CSV 报告
        </Button>
      </div>
    </div>
  );
};

export default MetricsPanel;
