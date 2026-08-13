import React, { useState, useRef, useEffect } from 'react';
import { Input, Button, Typography, Space, Tag, Spin, Select } from 'antd';
import { SendOutlined, UserOutlined, RobotOutlined, PlusOutlined } from '@ant-design/icons';
import { askQuestion } from '../api';
import type { ChatResponse } from '../types';

const { TextArea } = Input;
const { Text } = Typography;

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  sources?: ChatResponse['sources'];
  retrievalMode?: string;
  refusal?: boolean;
}

interface Session {
  id: string;
  title: string;
  messages: Message[];
}

interface Props {
  retrievalMode: string;
}

const newSession = (): Session => ({
  id: crypto.randomUUID(),
  title: '新对话',
  messages: [],
});

const ChatPanel: React.FC<Props> = ({ retrievalMode }) => {
  const initialRef = useRef<Session[] | null>(null);
  if (initialRef.current === null) {
    initialRef.current = [newSession()];
  }

  const [sessions, setSessions] = useState<Session[]>(initialRef.current);
  const [activeId, setActiveId] = useState<string>(initialRef.current[0].id);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const active = sessions.find(s => s.id === activeId) ?? sessions[0];

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [active?.messages]);

  const updateSession = (id: string, updater: (s: Session) => Session) => {
    setSessions(prev => prev.map(s => (s.id === id ? updater(s) : s)));
  };

  const handleNewSession = () => {
    const s = newSession();
    setSessions(prev => [s, ...prev]);
    setActiveId(s.id);
  };

  const handleSend = async () => {
    const q = input.trim();
    if (!q || loading || !active) return;
    setInput('');
    setLoading(true);

    const userMsg: Message = { id: crypto.randomUUID(), role: 'user', content: q };
    updateSession(active.id, s => ({
      ...s,
      title: s.title === '新对话' ? (q.length > 20 ? q.slice(0, 20) + '…' : q) : s.title,
      messages: [...s.messages, userMsg],
    }));

    try {
      const resp = await askQuestion(q, active.id, retrievalMode);
      const assistantMsg: Message = {
        id: crypto.randomUUID(),
        role: 'assistant',
        content: resp.content,
        sources: resp.sources,
        retrievalMode: resp.retrievalMode,
        refusal: resp.refusal,
      };
      updateSession(active.id, s => ({ ...s, messages: [...s.messages, assistantMsg] }));
    } catch {
      updateSession(active.id, s => ({
        ...s,
        messages: [...s.messages, {
          id: crypto.randomUUID(), role: 'assistant',
          content: '抱歉，服务出错了，请稍后重试。',
        }],
      }));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', maxHeight: 'calc(100vh - 100px)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
        <Select
          value={activeId}
          onChange={setActiveId}
          style={{ flex: 1 }}
          options={sessions.map(s => ({ value: s.id, label: s.title }))}
        />
        <Button icon={<PlusOutlined />} onClick={handleNewSession}>新建对话</Button>
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '0 8px', marginBottom: 12 }}>
        {(!active || active.messages.length === 0) && (
          <div style={{ textAlign: 'center', color: '#999', marginTop: 120 }}>
            <RobotOutlined style={{ fontSize: 48 }} />
            <p>向知识库提问，开始对话</p>
            <Tag color="blue">检索模式: {retrievalMode}</Tag>
          </div>
        )}
        {active && active.messages.map(msg => (
          <div key={msg.id} style={{
            marginBottom: 16,
            display: 'flex', flexDirection: 'column',
            alignItems: msg.role === 'user' ? 'flex-end' : 'flex-start',
          }}>
            <Space align="start">
              {msg.role === 'assistant' && <RobotOutlined style={{ color: '#1677ff' }} />}
              <div style={{
                maxWidth: '90%',
                padding: '10px 14px',
                borderRadius: 12,
                background: msg.role === 'user' ? '#1677ff' : '#f0f0f0',
                color: msg.role === 'user' ? '#fff' : '#333',
              }}>
                <div style={{ whiteSpace: 'pre-wrap' }}>{msg.content}</div>
                {msg.retrievalMode && (
                  <Tag style={{ marginTop: 6 }} color="green">模式: {msg.retrievalMode}</Tag>
                )}
                {msg.refusal && <Tag style={{ marginTop: 6 }} color="orange">拒答</Tag>}
                {msg.sources && msg.sources.length > 0 && (
                  <div style={{ marginTop: 6 }}>
                    <Text type="secondary" style={{ fontSize: 12 }}>来源: </Text>
                    {msg.sources.map((s, i) => (
                      <Tag key={i} color="blue" style={{ fontSize: 11, marginBottom: 2 }}>
                        {s.fileName}
                      </Tag>
                    ))}
                  </div>
                )}
              </div>
              {msg.role === 'user' && <UserOutlined style={{ color: '#1677ff' }} />}
            </Space>
          </div>
        ))}
        {loading && <Spin style={{ display: 'block', margin: '8px auto' }} />}
        <div ref={messagesEndRef} />
      </div>

      <div style={{ display: 'flex', gap: 8 }}>
        <TextArea
          value={input}
          onChange={e => setInput(e.target.value)}
          onPressEnter={e => { if (!e.shiftKey) { e.preventDefault(); handleSend(); } }}
          placeholder="输入问题，Enter 发送，Shift+Enter 换行"
          rows={2}
          disabled={loading}
        />
        <Button type="primary" icon={<SendOutlined />} onClick={handleSend} loading={loading}>
          发送
        </Button>
      </div>
    </div>
  );
};

export default ChatPanel;
