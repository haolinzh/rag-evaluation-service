import React, { useState, useEffect } from 'react';
import { Card, Statistic, Row, Col, Button, Typography, message } from 'antd';
import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { fetchReport } from '../api';

const { Text } = Typography;

const MetricsPanel: React.FC = () => {
  const [metrics, setMetrics] = useState({ requests: 0, cacheRate: 0, refusalRate: 0, p50: 0, p95: 0 });

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

  // Poll metrics from report endpoint periodically
  useEffect(() => {
    const poll = async () => {
      try {
        const { fetchReport } = await import('../api');
        // Simple polling for display - in production this would be a dedicated metrics endpoint
      } catch {}
    };
    const interval = setInterval(poll, 10000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div>
      <Typography.Title level={5} style={{ marginTop: 0 }}>运维指标</Typography.Title>

      <Row gutter={[12, 12]}>
        <Col span={12}>
          <Card size="small">
            <Statistic title="P50 延迟" value={metrics.p50} suffix="ms" precision={0} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <Statistic title="P95 延迟" value={metrics.p95} suffix="ms" precision={0} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <Statistic title="请求数" value={metrics.requests} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <Statistic title="缓存命中率" value={metrics.cacheRate} suffix="%" precision={1} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <Statistic title="拒答率" value={metrics.refusalRate} suffix="%" precision={1} />
          </Card>
        </Col>
      </Row>

      <div style={{ marginTop: 16 }}>
        <Button icon={<ReloadOutlined />} size="small" style={{ marginRight: 8 }}>
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
