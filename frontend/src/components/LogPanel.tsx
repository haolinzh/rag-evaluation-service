import React, { useState, useEffect, useCallback } from 'react';
import { Table, Button, Typography, Tag, Space, message, Popconfirm } from 'antd';
import { ReloadOutlined, DeleteOutlined, FolderOpenOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { fetchLogs, clearLogs } from '../api';
import type { RequestLog } from '../types';

const statusColor: Record<string, string> = {
  success: 'green',
  refused: 'orange',
  error: 'red',
};

interface Props {
  onOpenManagement: () => void;
}

const LogPanel: React.FC<Props> = ({ onOpenManagement }) => {
  const [logs, setLogs] = useState<RequestLog[]>([]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    try {
      setLogs(await fetchLogs(200));
    } catch {
      if (!silent) message.error('日志加载失败');
    } finally {
      if (!silent) setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
    const interval = setInterval(() => refresh(true), 5000);
    return () => clearInterval(interval);
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
    {
      title: '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 128,
      render: (v: string) => v?.replace('T', ' ').slice(5, 16),
    },
    {
      title: '模式',
      dataIndex: 'retrievalMode',
      key: 'retrievalMode',
      width: 76,
      ellipsis: true,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 62,
      render: (v: string) => <Tag color={statusColor[v] ?? 'default'}>{v}</Tag>,
    },
    {
      title: '耗时',
      dataIndex: 'responseTimeMs',
      key: 'responseTimeMs',
      width: 66,
      render: (v: number) => `${v}ms`,
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
        <Typography.Title level={5} style={{ margin: 0 }}>日志</Typography.Title>
        <Space size={4}>
          <Button type="text" size="small" icon={<ReloadOutlined />} onClick={() => refresh()} loading={loading}>刷新</Button>
          <Button type="text" size="small" icon={<FolderOpenOutlined />} onClick={onOpenManagement}>详情</Button>
          <Popconfirm title="确定清空所有日志？" onConfirm={handleClear} okText="清空" cancelText="取消">
            <Button type="text" size="small" danger icon={<DeleteOutlined />}>清空</Button>
          </Popconfirm>
        </Space>
      </div>

      <Table<RequestLog>
        rowKey="id"
        size="small"
        columns={columns}
        dataSource={logs}
        loading={loading}
        pagination={{ pageSize: 15, size: 'small', showSizeChanger: false }}
        scroll={{ x: 340 }}
      />
    </div>
  );
};

export default LogPanel;
