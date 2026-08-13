import React, { useState, useEffect } from 'react';
import { Table, Button, Drawer, Tag, Typography, Space, Empty, message } from 'antd';
import { ArrowLeftOutlined, EyeOutlined } from '@ant-design/icons';
import type { DocumentMeta, ChunkPreview } from '../types';
import { listDocuments, getDocumentChunks } from '../api';

interface Props {
  onBack: () => void;
}

const formatTime = (s?: string) => {
  if (!s) return '—';
  return s.replace('T', ' ').split('.')[0];
};

const formatSize = (bytes?: number) => {
  if (bytes == null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

const DocumentManagement: React.FC<Props> = ({ onBack }) => {
  const [documents, setDocuments] = useState<DocumentMeta[]>([]);
  const [loading, setLoading] = useState(false);
  const [previewDoc, setPreviewDoc] = useState<DocumentMeta | null>(null);
  const [previews, setPreviews] = useState<ChunkPreview[]>([]);
  const [previewLoading, setPreviewLoading] = useState(false);

  const refresh = async () => {
    setLoading(true);
    try {
      setDocuments(await listDocuments());
    } catch {
      message.error('加载文档列表失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { refresh(); }, []);

  const openPreview = async (doc: DocumentMeta) => {
    setPreviewDoc(doc);
    setPreviews([]);
    setPreviewLoading(true);
    try {
      setPreviews(await getDocumentChunks(doc.id));
    } catch {
      message.error('加载 chunk 预览失败');
    } finally {
      setPreviewLoading(false);
    }
  };

  const columns = [
    { title: '文件名', dataIndex: 'fileName', key: 'fileName', ellipsis: true },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => formatTime(v) },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', render: (v: string) => formatTime(v) },
    {
      title: 'Chunk 方式', key: 'splitMode',
      render: (_: unknown, r: DocumentMeta) => r.splitMode === 'delimiter'
        ? <Tag color="purple">特殊字符{r.delimiter ? ` (${r.delimiter})` : ''}</Tag>
        : r.splitMode === 'size' ? <Tag color="blue">按大小</Tag> : '—',
    },
    { title: 'Chunk 大小', dataIndex: 'chunkSize', key: 'chunkSize', render: (v?: number) => v != null ? `${v} 字符` : '—' },
    { title: 'Overlap', dataIndex: 'overlap', key: 'overlap', render: (v?: number) => v != null ? `${v} 字符` : '—' },
    { title: 'Chunk 数', dataIndex: 'chunkCount', key: 'chunkCount' },
    { title: '文件大小', dataIndex: 'fileSize', key: 'fileSize', render: (v?: number) => formatSize(v) },
    {
      title: '操作', key: 'action',
      render: (_: unknown, r: DocumentMeta) => (
        <Button size="small" icon={<EyeOutlined />} onClick={() => openPreview(r)}>预览</Button>
      ),
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Space style={{ marginBottom: 16 }} align="center">
        <Button icon={<ArrowLeftOutlined />} onClick={onBack}>返回</Button>
        <Typography.Title level={4} style={{ margin: 0 }}>文档管理</Typography.Title>
      </Space>

      <Table
        rowKey="id"
        dataSource={documents}
        columns={columns}
        loading={loading}
        pagination={{ pageSize: 10 }}
        scroll={{ x: 1000 }}
      />

      <Drawer
        title={previewDoc ? `Chunk 预览 — ${previewDoc.fileName}` : 'Chunk 预览'}
        open={!!previewDoc}
        onClose={() => setPreviewDoc(null)}
        width={520}
      >
        {previewLoading && <div style={{ textAlign: 'center', padding: 24 }}>加载中…</div>}
        {!previewLoading && previews.length === 0 && <Empty description="暂无 chunk" />}
        {!previewLoading && previews.map(p => (
          <div key={p.chunkIndex} style={{ marginBottom: 12, padding: 10, background: '#fafafa', borderRadius: 8 }}>
            <Space size={6} style={{ marginBottom: 4 }}>
              <Tag color="blue">#{p.chunkIndex}</Tag>
              {p.chapter && <Tag color="green">{p.chapter}</Tag>}
              {p.section && <Tag color="cyan">{p.section}</Tag>}
            </Space>
            <div style={{ fontSize: 13, color: '#555', whiteSpace: 'pre-wrap' }}>{p.snippet}</div>
          </div>
        ))}
      </Drawer>
    </div>
  );
};

export default DocumentManagement;
