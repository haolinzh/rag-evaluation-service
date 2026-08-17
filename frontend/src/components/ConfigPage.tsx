import React, { useState, useEffect } from 'react';
import { Form, Select, InputNumber, Switch, Button, Typography, Space, Card, Alert, message, Row, Col, Spin } from 'antd';
import { ArrowLeftOutlined, SaveOutlined, SettingOutlined } from '@ant-design/icons';
import { fetchConfig, updateConfig } from '../api';
import type { SystemConfig } from '../types';

interface Props {
  onBack: () => void;
  onSaved: (mode: string) => void;
}

interface FormValues {
  mode: string;
  topK: number;
  recallSizeMultiplier: number;
  rrfK: number;
  rerankCandidates: number;
  similarityThreshold: number;
  chat: string;
  embedding: string;
  rerank: string;
  minSimilarity: number;
  enableOutOfScopeCheck: boolean;
  outOfScopeThreshold: number;
  forbiddenKeywords: string[];
  enabled: boolean;
  ttlSeconds: number;
}

const ConfigPage: React.FC<Props> = ({ onBack, onSaved }) => {
  const [form] = Form.useForm<FormValues>();
  const [config, setConfig] = useState<SystemConfig | null>(null);
  const [saving, setSaving] = useState(false);

  const embeddingValue = Form.useWatch('embedding', form);

  useEffect(() => {
    fetchConfig()
      .then((c) => {
        setConfig(c);
        form.setFieldsValue({
          mode: c.retrieval.mode,
          topK: c.retrieval.topK,
          recallSizeMultiplier: c.retrieval.recallSizeMultiplier,
          rrfK: c.retrieval.rrfK,
          rerankCandidates: c.retrieval.rerankCandidates,
          similarityThreshold: c.retrieval.similarityThreshold,
          chat: c.models.chat,
          embedding: c.models.embedding,
          rerank: c.models.rerank,
          minSimilarity: c.safety.minSimilarity,
          enableOutOfScopeCheck: c.safety.enableOutOfScopeCheck,
          outOfScopeThreshold: c.safety.outOfScopeThreshold,
          forbiddenKeywords: c.safety.forbiddenKeywords.split(',').map((s) => s.trim()).filter(Boolean),
          enabled: c.cache.enabled,
          ttlSeconds: c.cache.ttlSeconds,
        });
      })
      .catch(() => message.error('配置加载失败'));
  }, [form]);

  const modelGroup = (g: string) =>
    (config?.modelOptions ?? []).filter((o) => o.group === g).map((o) => ({ label: o.label, value: o.id }));

  const keywordOptions =
    config?.safety.forbiddenKeywords.split(',').map((s) => s.trim()).filter(Boolean).map((k) => ({ label: k, value: k })) ?? [];

  const selectedEmbedding = config?.modelOptions.find((o) => o.id === embeddingValue);
  const dimMismatch =
    !!selectedEmbedding && selectedEmbedding.dimensions != null && selectedEmbedding.dimensions !== config?.embeddingDimension;

  const onFinish = async (v: FormValues) => {
    if (!config) return;
    const next: SystemConfig = {
      retrieval: {
        mode: v.mode,
        topK: v.topK,
        recallSizeMultiplier: v.recallSizeMultiplier,
        rrfK: v.rrfK,
        rerankCandidates: v.rerankCandidates,
        similarityThreshold: v.similarityThreshold,
      },
      models: { chat: v.chat, embedding: v.embedding, rerank: v.rerank },
      safety: {
        minSimilarity: v.minSimilarity,
        enableOutOfScopeCheck: v.enableOutOfScopeCheck,
        outOfScopeThreshold: v.outOfScopeThreshold,
        forbiddenKeywords: v.forbiddenKeywords.join(','),
      },
      cache: { enabled: v.enabled, ttlSeconds: v.ttlSeconds },
      modelOptions: config.modelOptions,
      embeddingDimension: config.embeddingDimension,
    };
    setSaving(true);
    try {
      await updateConfig(next);
      onSaved(v.mode);
      message.success('配置已保存，即时生效');
    } catch (e: any) {
      message.error(e?.response?.data?.error ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  if (!config) {
    return (
      <div style={{ padding: 48, display: 'flex', justifyContent: 'center' }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div style={{ padding: 24, maxWidth: 960, margin: '0 auto' }}>
      <Space style={{ marginBottom: 16 }} align="center">
        <Button icon={<ArrowLeftOutlined />} onClick={onBack}>返回</Button>
        <Typography.Title level={4} style={{ margin: 0 }}><SettingOutlined /> 系统配置</Typography.Title>
        <Button type="primary" icon={<SaveOutlined />} onClick={() => form.submit()} loading={saving}>保存</Button>
      </Space>

      <Form form={form} layout="vertical" onFinish={onFinish} requiredMark={false}>
        <Card title="检索参数" size="small" style={{ marginBottom: 16 }}>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item label="检索模式" name="mode" rules={[{ required: true }]}>
                <Select
                  options={[
                    { label: 'vector（仅向量）', value: 'vector' },
                    { label: 'hybrid（关键词+向量 RRF）', value: 'hybrid' },
                    { label: 'hybrid-rerank（RRF+精排）', value: 'hybrid-rerank' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="top-k" name="topK" rules={[{ required: true }]}>
                <InputNumber min={1} max={50} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="召回倍数 (recall-size-multiplier)" name="recallSizeMultiplier" rules={[{ required: true }]}>
                <InputNumber min={1} max={20} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="RRF k" name="rrfK" rules={[{ required: true }]}>
                <InputNumber min={1} max={200} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="精排候选数 (rerank-candidates)" name="rerankCandidates" rules={[{ required: true }]}>
                <InputNumber min={1} max={200} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="相似度阈值 (similarity-threshold)" name="similarityThreshold" rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        </Card>

        <Card title="模型" size="small" style={{ marginBottom: 16 }}>
          {dimMismatch && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message={`向量模型 ${selectedEmbedding?.id} 维度为 ${selectedEmbedding?.dimensions}，与当前向量库 (${config.embeddingDimension} 维) 不一致，切换后需重新入库。`}
            />
          )}
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item label="对话模型" name="chat" rules={[{ required: true }]}>
                <Select options={modelGroup('chat')} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="向量模型" name="embedding" rules={[{ required: true }]}>
                <Select options={modelGroup('embedding')} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="精排模型" name="rerank" rules={[{ required: true }]}>
                <Select options={modelGroup('rerank')} />
              </Form.Item>
            </Col>
          </Row>
        </Card>

        <Card title="安全" size="small" style={{ marginBottom: 16 }}>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item label="最小相似度 (min-similarity)" name="minSimilarity" rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="越界阈值 (out-of-scope-threshold)" name="outOfScopeThreshold" rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="启用越界检测" name="enableOutOfScopeCheck" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item label="禁用关键词" name="forbiddenKeywords">
                <Select mode="tags" placeholder="输入后回车添加" options={keywordOptions} />
              </Form.Item>
            </Col>
          </Row>
        </Card>

        <Card title="缓存" size="small" style={{ marginBottom: 16 }}>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item label="启用语义缓存" name="enabled" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="缓存 TTL（秒）" name="ttlSeconds" rules={[{ required: true }]}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        </Card>
      </Form>
    </div>
  );
};

export default ConfigPage;
