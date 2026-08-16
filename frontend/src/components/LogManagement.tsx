import React, { useState, useEffect, useCallback } from 'react';
import { Table, Button, Typography, Tag, Space, Descriptions, message, Popconfirm } from 'antd';
import { ArrowLeftOutlined, ReloadOutlined, DeleteOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { fetchLogs, clearLogs } from '../api';
import type { RequestLog } from '../types';

interface Props {
  onBack: () => void;
}

const statusColor: Record<string, string> = {
  success: 'green',
  refused: 'orange',
  error: 'red',
};

const formatTime = (s?: string) => {
  if (!s) return '—';
  return s.replace('T', ' ').split('.')[0];
};

interface ChunkRow {
  rank: number;
  fileName: string;
  chunkId: string;
  score: number;
  source: string;
  chapter?: string;
  section?: string;
  snippet?: string;
  sourceDetails?: Record<string, number>;
}

const sourceColor: Record<string, string> = {
  keyword: 'geekblue',
  semantic: 'purple',
  rrf: 'cyan',
  rerank: 'gold',
};

const parseChunks = (json?: string | null): ChunkRow[] => {
  if (!json) return [];
  try {
    const arr = JSON.parse(json);
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
};

const renderChunks = (json?: string | null) => {
  const chunks = parseChunks(json);
  if (chunks.length === 0) return '—';
  return (
    <div style={{ maxHeight: 260, overflowY: 'auto' }}>
      {chunks.map((c, i) => (
        <div key={i} style={{ borderBottom: '1px solid #f0f0f0', padding: '6px 0', fontSize: 12 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <Tag color={sourceColor[c.source] ?? 'default'} style={{ margin: 0 }}>{c.source}</Tag>
            <strong>#{c.rank}</strong>
            <span>{c.fileName}</span>
            {c.chapter && <span style={{ color: '#999' }}>{c.chapter}{c.section ? ' · ' + c.section : ''}</span>}
            <span style={{ color: '#1677ff' }}>score {typeof c.score === 'number' ? c.score.toFixed(4) : c.score}</span>
          </div>
          {c.sourceDetails && Object.keys(c.sourceDetails).length > 0 && (
            <div style={{ color: '#999', marginTop: 2 }}>
              {Object.entries(c.sourceDetails).map(([k, v]) => (
                <span key={k} style={{ marginRight: 10 }}>{k}: {typeof v === 'number' ? v.toFixed(4) : v}</span>
              ))}
            </div>
          )}
          {c.snippet && <div style={{ color: '#666', marginTop: 2, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{c.snippet}</div>}
        </div>
      ))}
    </div>
  );
};

const LogManagement: React.FC<Props> = ({ onBack }) => {
  const [logs, setLogs] = useState<RequestLog[]>([]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setLogs(await fetchLogs(1000));
    } catch {
      message.error('日志加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const handleClear = async () => {
    try {
      await clearLogs();
      message.success('日志已清空');
      setLogs([]);
    } catch {
      message.error('清空日志失败');
    }
  };

  const columns: ColumnsType<RequestLog> = [
    { title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 160, render: (v: string) => formatTime(v) },
    { title: '请求 ID', dataIndex: 'requestId', key: 'requestId', width: 240, ellipsis: true },
    { title: 'Session', dataIndex: 'sessionId', key: 'sessionId', width: 200, ellipsis: true },
    { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
    { title: '模式', dataIndex: 'retrievalMode', key: 'retrievalMode', width: 90 },
    { title: '模型', dataIndex: 'model', key: 'model', width: 100 },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (v: string) => <Tag color={statusColor[v] ?? 'default'}>{v}</Tag>,
    },
    { title: '耗时', dataIndex: 'responseTimeMs', key: 'responseTimeMs', width: 90, render: (v: number) => `${v}ms` },
    { title: 'LLM 调用', dataIndex: 'llmCallCount', key: 'llmCallCount', width: 90 },
    { title: '命中文档', dataIndex: 'hitDocuments', key: 'hitDocuments', ellipsis: true },
  ];

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={onBack}>返回</Button>
        <Typography.Title level={4} style={{ margin: 0, flex: 1 }}>日志管理</Typography.Title>
        <Button icon={<ReloadOutlined />} onClick={refresh} loading={loading}>刷新</Button>
        <Popconfirm title="确定清空所有日志？" onConfirm={handleClear} okText="清空" cancelText="取消">
          <Button icon={<DeleteOutlined />} danger>清空日志</Button>
        </Popconfirm>
      </div>

      <Table<RequestLog>
        rowKey="id"
        dataSource={logs}
        columns={columns}
        loading={loading}
        pagination={{ pageSize: 20, showSizeChanger: true, pageSizeOptions: [20, 50, 100] }}
        scroll={{ x: 1200, y: 'calc(100vh - 220px)' }}
        expandable={{
          expandedRowRender: (r) => (
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label="请求 ID" span={2}>{r.requestId}</Descriptions.Item>
              <Descriptions.Item label="Session">{r.sessionId}</Descriptions.Item>
              <Descriptions.Item label="模型">{r.model}</Descriptions.Item>
              <Descriptions.Item label="问题" span={2}>{r.question}</Descriptions.Item>
              <Descriptions.Item label="回答" span={2}>{r.answer ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="命中文档" span={2}>{r.hitDocuments || '—'}</Descriptions.Item>
              <Descriptions.Item label="召回 chunk" span={2}>{renderChunks(r.retrievedChunks)}</Descriptions.Item>
              <Descriptions.Item label="重排候选" span={2}>{renderChunks(r.rerankCandidates)}</Descriptions.Item>
              <Descriptions.Item label="发送给 LLM 的内容" span={2}>
                {r.prompt ? (
                  <div style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 300, overflowY: 'auto', fontFamily: 'monospace', fontSize: 12 }}>{r.prompt}</div>
                ) : '—'}
              </Descriptions.Item>
              <Descriptions.Item label="LLM 调用次数">{r.llmCallCount}</Descriptions.Item>
              <Descriptions.Item label="总耗时">{r.responseTimeMs}ms</Descriptions.Item>
              <Descriptions.Item label="检索延迟">{r.retrievalLatencyMs}ms</Descriptions.Item>
              <Descriptions.Item label="生成延迟">{r.generationLatencyMs}ms</Descriptions.Item>
              <Descriptions.Item label="Embedding 延迟">{r.embeddingLatencyMs}ms</Descriptions.Item>
              <Descriptions.Item label="关键词检索延迟">{r.keywordLatencyMs}ms</Descriptions.Item>
              <Descriptions.Item label="向量检索延迟">{r.vectorLatencyMs}ms</Descriptions.Item>
              <Descriptions.Item label="重排延迟">{r.rerankLatencyMs}ms</Descriptions.Item>
              <Descriptions.Item label="缓存查询延迟">{r.cacheLookupLatencyMs}ms</Descriptions.Item>
              <Descriptions.Item label="缓存命中">{r.cacheHit ? '是' : '否'}</Descriptions.Item>
              <Descriptions.Item label="拒答">{r.refusal ? `是（${r.refusalReason ?? ''}）` : '否'}</Descriptions.Item>
              <Descriptions.Item label="Token（提示/补全）">
                {r.promptTokens} / {r.completionTokens}
              </Descriptions.Item>
              <Descriptions.Item label="召回 chunk 数">{r.chunksRetrieved}</Descriptions.Item>
              <Descriptions.Item label="关键词召回数">{r.keywordCount}</Descriptions.Item>
              <Descriptions.Item label="向量召回数">{r.vectorCount}</Descriptions.Item>
              <Descriptions.Item label="重叠 chunk 数">{r.overlapCount}</Descriptions.Item>
              <Descriptions.Item label="最高 chunk 分">{r.maxChunkScore.toFixed(3)}</Descriptions.Item>
              <Descriptions.Item label="PII 脱敏数">{r.piiRedactions}</Descriptions.Item>
            </Descriptions>
          ),
          rowExpandable: () => true,
        }}
      />
    </div>
  );
};

export default LogManagement;
