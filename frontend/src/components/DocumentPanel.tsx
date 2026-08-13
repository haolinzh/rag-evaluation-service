import React, { useState } from 'react';
import { Upload, Button, List, Popconfirm, Select, Space, Typography, message, Tag } from 'antd';
import { UploadOutlined, DeleteOutlined, ReloadOutlined, InboxOutlined } from '@ant-design/icons';
import type { DocumentMeta } from '../types';
import { uploadDocument, deleteDocument } from '../api';

const { Dragger } = Upload;
const { Text } = Typography;

interface Props {
  documents: DocumentMeta[];
  retrievalMode: string;
  onModeChange: (mode: string) => void;
  onRefresh: () => void;
}

const DocumentPanel: React.FC<Props> = ({ documents, retrievalMode, onModeChange, onRefresh }) => {
  const [uploading, setUploading] = useState(false);

  const handleUpload = async (file: File) => {
    setUploading(true);
    try {
      await uploadDocument(file);
      message.success(`${file.name} 上传成功`);
      onRefresh();
    } catch {
      message.error(`上传失败: ${file.name}`);
    } finally {
      setUploading(false);
    }
    return false; // prevent default upload
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteDocument(id);
      message.success('已删除');
      onRefresh();
    } catch {
      message.error('删除失败');
    }
  };

  const formatSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  return (
    <div>
      <Typography.Title level={5} style={{ marginTop: 0 }}>文档管理</Typography.Title>

      <Space direction="vertical" style={{ width: '100%', marginBottom: 16 }}>
        <Space>
          <span>检索模式:</span>
          <Select value={retrievalMode} onChange={onModeChange} style={{ width: 140 }}
            options={[
              { value: 'hybrid', label: 'Hybrid (混合)' },
              { value: 'vector', label: 'Vector (向量)' },
            ]} />
        </Space>
      </Space>

      <Dragger
        accept=".pdf,.docx,.txt"
        showUploadList={false}
        beforeUpload={handleUpload}
        disabled={uploading}
        style={{ marginBottom: 16 }}
      >
        <p className="ant-upload-drag-icon"><InboxOutlined /></p>
        <p className="ant-upload-text">点击或拖拽文件上传</p>
        <p className="ant-upload-hint">支持 PDF, DOCX, TXT</p>
      </Dragger>

      <div style={{ marginBottom: 8 }}>
        <Button icon={<ReloadOutlined />} onClick={onRefresh} size="small">刷新列表</Button>
      </div>

      <List
        dataSource={documents}
        locale={{ emptyText: '暂无文档' }}
        renderItem={(doc) => (
          <List.Item
            actions={[
              <Popconfirm title="确认删除？" onConfirm={() => handleDelete(doc.id)} key="del">
                <Button size="small" danger icon={<DeleteOutlined />} />
              </Popconfirm>
            ]}
          >
            <List.Item.Meta
              title={<Text ellipsis style={{ maxWidth: 200 }}>{doc.fileName}</Text>}
              description={
                <Space size={4}>
                  <Tag>{formatSize(doc.fileSize)}</Tag>
                  <Tag>{doc.chunkCount} chunks</Tag>
                </Space>
              }
            />
          </List.Item>
        )}
      />
    </div>
  );
};

export default DocumentPanel;
