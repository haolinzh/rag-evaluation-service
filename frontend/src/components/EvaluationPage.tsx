import React, { useState, useEffect } from 'react';
import {
  Button, Typography, Space, Card, Checkbox, Switch, Progress, Table, Tabs, Tag, Alert, Spin, Empty,
} from 'antd';
import { ArrowLeftOutlined, ExperimentOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { runEvaluation, fetchEvaluationQuestions } from '../api';
import type { EvaluationEvent, EvaluationSummary, EvaluationQuestionResult } from '../types';

interface Props {
  onBack: () => void;
}

const MODE_OPTIONS = [
  { label: 'vector（仅向量）', value: 'vector' },
  { label: 'hybrid（关键词+向量 RRF）', value: 'hybrid' },
  { label: 'hybrid-rerank（RRF+精排）', value: 'hybrid-rerank' },
];

type MetricKey = Exclude<keyof EvaluationSummary, 'mode'>;

const METRICS: { key: MetricKey; label: string; digits: number }[] = [
  { key: 'avgFaithfulness', label: 'Faithfulness', digits: 3 },
  { key: 'avgContextPrecision', label: 'Context Precision', digits: 3 },
  { key: 'avgAnswerCompliance', label: 'Answer Compliance', digits: 3 },
  { key: 'avgRefusalAppropriate', label: 'Refusal Appropriateness', digits: 3 },
  { key: 'avgStyleConsistent', label: 'Style Consistency', digits: 3 },
  { key: 'avgLatencyMs', label: 'Avg Latency (ms)', digits: 1 },
  { key: 'p50LatencyMs', label: 'P50 Latency (ms)', digits: 1 },
  { key: 'p95LatencyMs', label: 'P95 Latency (ms)', digits: 1 },
];

const fmt = (v: number | undefined | null, digits: number) =>
  typeof v === 'number' ? v.toFixed(digits) : '-';

const EvaluationPage: React.FC<Props> = ({ onBack }) => {
  const [modes, setModes] = useState<string[]>(['vector', 'hybrid', 'hybrid-rerank']);
  const [clearCache, setClearCache] = useState(true);
  const [running, setRunning] = useState(false);
  const [questionCount, setQuestionCount] = useState<number | null>(null);
  const [currentMode, setCurrentMode] = useState<string | null>(null);
  const [currentQuestion, setCurrentQuestion] = useState<string | null>(null);
  const [doneCount, setDoneCount] = useState(0);
  const [totalCount, setTotalCount] = useState(0);
  const [summaries, setSummaries] = useState<EvaluationSummary[]>([]);
  const [resultsByMode, setResultsByMode] = useState<Record<string, EvaluationQuestionResult[]>>({});
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchEvaluationQuestions().then((qs) => setQuestionCount(qs.length)).catch(() => {});
  }, []);

  const handleEvent = (evt: EvaluationEvent) => {
    switch (evt.type) {
      case 'start':
        setTotalCount(evt.modes.length * evt.totalQuestions);
        break;
      case 'mode_start':
        setCurrentMode(evt.mode);
        break;
      case 'question_start':
        setCurrentQuestion(evt.question);
        break;
      case 'question_done':
        setResultsByMode((prev) => ({ ...prev, [evt.mode]: [...(prev[evt.mode] ?? []), evt.result] }));
        setDoneCount((c) => c + 1);
        break;
      case 'question_error':
        setResultsByMode((prev) => ({
          ...prev,
          [evt.mode]: [...(prev[evt.mode] ?? []), {
            questionId: evt.questionId, question: evt.question, error: evt.message,
          } as EvaluationQuestionResult],
        }));
        setDoneCount((c) => c + 1);
        break;
      case 'mode_done':
        setSummaries((prev) => [...prev, evt.summary]);
        break;
      case 'done':
        setSummaries(evt.report.summaries);
        setResultsByMode(evt.report.results);
        setRunning(false);
        setCurrentMode(null);
        setCurrentQuestion(null);
        break;
      case 'error':
        setError(evt.message);
        setRunning(false);
        break;
    }
  };

  const start = async () => {
    const runModes = modes.length ? modes : ['vector', 'hybrid', 'hybrid-rerank'];
    setRunning(true);
    setError(null);
    setSummaries([]);
    setResultsByMode({});
    setDoneCount(0);
    setTotalCount(0);
    setCurrentMode(null);
    setCurrentQuestion(null);
    try {
      await runEvaluation(runModes, clearCache, handleEvent);
    } catch (e: any) {
      setError(e?.message ?? '测评请求失败');
      setRunning(false);
    }
  };

  const progressPercent = totalCount > 0 ? Math.round((doneCount / totalCount) * 100) : 0;

  const summaryRows = METRICS.map((m) => {
    const row: Record<string, string> = { key: m.label, label: m.label };
    for (const s of summaries) row[s.mode] = fmt(s[m.key], m.digits);
    return row;
  });

  const comparisonColumns = [
    { title: '指标', dataIndex: 'label', key: 'label', width: 200 },
    ...summaries.map((s) => ({ title: s.mode, dataIndex: s.mode, key: s.mode })),
  ];

  const questionColumns = [
    { title: 'ID', dataIndex: 'questionId', key: 'questionId', width: 64 },
    { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
    { title: '类型', dataIndex: 'expectedType', key: 'expectedType', width: 90, render: (v: string) => v ?? '-' },
    {
      title: '拒答', dataIndex: 'refusal', key: 'refusal', width: 70,
      render: (v: boolean) => (v ? <Tag color="red">拒答</Tag> : <Tag color="green">回答</Tag>),
    },
    { title: '延迟(ms)', dataIndex: 'latencyMs', key: 'latencyMs', width: 90, render: (v: number) => fmt(v, 0) },
    { title: 'F', dataIndex: 'faithfulness', key: 'faithfulness', width: 56, render: (v: number) => fmt(v, 2) },
    { title: 'CP', dataIndex: 'contextPrecision', key: 'contextPrecision', width: 56, render: (v: number) => fmt(v, 2) },
    { title: 'AC', dataIndex: 'answerCompliance', key: 'answerCompliance', width: 56, render: (v: number) => fmt(v, 2) },
    { title: 'RA', dataIndex: 'refusalAppropriate', key: 'refusalAppropriate', width: 56, render: (v: number) => fmt(v, 2) },
    { title: 'SC', dataIndex: 'styleConsistent', key: 'styleConsistent', width: 56, render: (v: number) => fmt(v, 2) },
  ];

  const expandedRowRender = (r: EvaluationQuestionResult) => (
    <div style={{ padding: '0 8px' }}>
      {r.error && <Alert type="error" message={r.error} style={{ marginBottom: 12 }} showIcon />}
      <Typography.Paragraph style={{ marginBottom: 8 }}>
        <Typography.Text strong>回答：</Typography.Text>
        <span style={{ whiteSpace: 'pre-wrap' }}>{r.answer || '(空)'}</span>
      </Typography.Paragraph>
      {r.sources && r.sources.length > 0 && (
        <div>
          <Typography.Text strong>来源：</Typography.Text>
          <ul style={{ margin: 4, paddingLeft: 20 }}>
            {r.sources.map((s, i) => (
              <li key={i} style={{ marginBottom: 4 }}>
                <Tag color="blue">{s.fileName}</Tag>
                <span style={{ color: '#888' }}>{s.snippet}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );

  const orderedModes = summaries.map((s) => s.mode).filter((m) => resultsByMode[m]);

  return (
    <div style={{ height: '100vh', overflowY: 'auto', padding: 24 }}>
      <Space style={{ marginBottom: 16 }} align="center" wrap>
        <Button icon={<ArrowLeftOutlined />} onClick={onBack} disabled={running}>返回</Button>
        <Typography.Title level={4} style={{ margin: 0 }}>
          <ExperimentOutlined /> 一键测评
        </Typography.Title>
        {questionCount != null && <Tag>测试集 {questionCount} 题</Tag>}
      </Space>

      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap size="middle">
          <span>检索模式：</span>
          <Checkbox.Group
            options={MODE_OPTIONS}
            value={modes}
            onChange={(vals) => setModes(vals as string[])}
            disabled={running}
          />
        </Space>
        <Space wrap size="middle" style={{ marginTop: 12 }}>
          <span>清空语义缓存：</span>
          <Switch checked={clearCache} onChange={setClearCache} disabled={running} />
          <Button
            type="primary"
            icon={<ThunderboltOutlined />}
            onClick={start}
            loading={running}
          >
            {running ? '测评中…' : '开始测评'}
          </Button>
        </Space>
      </Card>

      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} showIcon closable onClose={() => setError(null)} />}

      {running && (
        <Card size="small" style={{ marginBottom: 16 }}>
          <Space direction="vertical" style={{ width: '100%' }}>
            <Progress percent={progressPercent} status="active" />
            <Typography.Text type="secondary">
              {currentMode ? `当前模式：${currentMode}` : '准备中…'}
              {currentQuestion ? `　|　${currentQuestion}` : ''}
            </Typography.Text>
            <Typography.Text type="secondary">已完成 {doneCount}/{totalCount || '…'} 题</Typography.Text>
          </Space>
        </Card>
      )}

      {summaries.length === 0 && !running && (
        <Empty description="选择检索模式后点击「开始测评」" style={{ marginTop: 48 }} />
      )}

      {summaries.length > 0 && (
        <Card size="small" title="三模式对比" style={{ marginBottom: 16 }}>
          <Table
            columns={comparisonColumns}
            dataSource={summaryRows}
            pagination={false}
            size="small"
            rowKey="key"
          />
        </Card>
      )}

      {orderedModes.length > 0 && (
        <Card size="small" title="逐题明细">
          <Tabs
            items={orderedModes.map((m) => ({
              key: m,
              label: m,
              children: (
                <Table
                  columns={questionColumns}
                  dataSource={resultsByMode[m]}
                  pagination={false}
                  size="small"
                  rowKey="questionId"
                  expandable={{ expandedRowRender, rowExpandable: () => true }}
                />
              ),
            }))}
          />
        </Card>
      )}
    </div>
  );
};

export default EvaluationPage;
