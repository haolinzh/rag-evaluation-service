import React, { useState, useRef, useEffect } from 'react';
import { Input, Button, Typography, Space, Tag, Spin } from 'antd';
import { SendOutlined, UserOutlined, RobotOutlined, PlusOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { streamAsk, getChatHistory, deleteChatHistory } from '../api';
import type { ChatResponse, ChatMessage, Source } from '../types';
import '../markdown.css';

const { TextArea } = Input;
const { Text } = Typography;

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  thinking?: string;
  sources?: ChatResponse['sources'];
  retrievalMode?: string;
  refusal?: boolean;
}

interface Session {
  id: string;
  title: string;
  messages: Message[];
  loaded: boolean;
}

interface Props {
  retrievalMode: string;
}

interface StoredSession {
  id: string;
  title: string;
}

const STORAGE_KEY = 'rag-chat-sessions';

const newSession = (): Session => ({
  id: crypto.randomUUID(),
  title: '新对话',
  messages: [],
  loaded: true,
});

const readStored = (): { sessions: StoredSession[]; activeId: string } | null => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed?.sessions) || parsed.sessions.length === 0) return null;
    return { sessions: parsed.sessions, activeId: parsed.activeId ?? parsed.sessions[0].id };
  } catch {
    return null;
  }
};

const writeStored = (sessions: Session[], activeId: string) => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      sessions: sessions.map(s => ({ id: s.id, title: s.title })),
      activeId,
    }));
  } catch {}
};

const parseSources = (raw?: string | null): Source[] | undefined => {
  if (!raw) return undefined;
  try {
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? (arr as Source[]) : undefined;
  } catch {
    return undefined;
  }
};

const ChatPanel: React.FC<Props> = ({ retrievalMode }) => {
  const initialRef = useRef<{ sessions: Session[]; activeId: string } | null>(null);
  if (initialRef.current === null) {
    const stored = readStored();
    if (stored) {
      const sessions = stored.sessions.map(m => ({
        id: m.id, title: m.title, messages: [], loaded: false,
      }));
      const activeId = sessions.some(s => s.id === stored.activeId)
        ? stored.activeId : sessions[0].id;
      initialRef.current = { sessions, activeId };
    } else {
      const s = newSession();
      initialRef.current = { sessions: [s], activeId: s.id };
    }
  }

  const [sessions, setSessions] = useState<Session[]>(initialRef.current.sessions);
  const [activeId, setActiveId] = useState<string>(initialRef.current.activeId);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [hoverId, setHoverId] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingTitle, setEditingTitle] = useState('');
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const messagesContainerRef = useRef<HTMLDivElement>(null);

  const active = sessions.find(s => s.id === activeId) ?? sessions[0];

  useEffect(() => {
    writeStored(sessions, activeId);
  }, [sessions, activeId]);

  useEffect(() => {
    const target = sessions.find(s => s.id === activeId);
    if (!target || target.loaded) return;
    getChatHistory(target.id)
      .then(history => {
        setSessions(prev => prev.map(s => s.id === target.id
          ? {
              ...s,
              loaded: true,
              messages: history.map((m: ChatMessage) => ({
                id: String(m.id),
                role: m.role as 'user' | 'assistant',
                content: m.content,
                thinking: m.thinking ?? undefined,
                retrievalMode: m.retrievalMode ?? undefined,
                sources: parseSources(m.sources),
              })),
            }
          : s));
      })
      .catch(() => {
        setSessions(prev => prev.map(s => (s.id === target.id ? { ...s, loaded: true } : s)));
      });
  }, [activeId]);

  useEffect(() => {
    const el = messagesContainerRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [active?.messages]);

  const updateSession = (id: string, updater: (s: Session) => Session) => {
    setSessions(prev => prev.map(s => (s.id === id ? updater(s) : s)));
  };

  const handleNewSession = () => {
    const s = newSession();
    setSessions(prev => [s, ...prev]);
    setActiveId(s.id);
  };

  const handleDelete = (id: string) => {
    const remaining = sessions.filter(s => s.id !== id);
    const next = remaining.length > 0 ? remaining : [newSession()];
    setSessions(next);
    if (activeId === id) {
      setActiveId(next[0].id);
    }
    deleteChatHistory(id).catch(() => {});
  };

  const startRename = (s: Session) => {
    setEditingId(s.id);
    setEditingTitle(s.title);
  };

  const commitRename = () => {
    if (editingId) {
      const t = editingTitle.trim();
      if (t) {
        updateSession(editingId, s => ({ ...s, title: t }));
      }
    }
    setEditingId(null);
    setEditingTitle('');
  };

  const handleSend = async () => {
    const q = input.trim();
    if (!q || loading || !active) return;
    setInput('');
    setLoading(true);

    const userMsg: Message = { id: crypto.randomUUID(), role: 'user', content: q };
    const assistantId = crypto.randomUUID();
    const assistantMsg: Message = { id: assistantId, role: 'assistant', content: '' };
    updateSession(active.id, s => ({
      ...s,
      title: s.title === '新对话' ? (q.length > 20 ? q.slice(0, 20) + '…' : q) : s.title,
      messages: [...s.messages, userMsg, assistantMsg],
    }));

    const patchAssistant = (patch: Partial<Message>) => {
      updateSession(active.id, s => ({
        ...s,
        messages: s.messages.map(m => (m.id === assistantId ? { ...m, ...patch } : m)),
      }));
    };

    let accThinking = '';
    let accContent = '';

    try {
      await streamAsk(q, active.id, retrievalMode, (evt) => {
        if (evt.type === 'thinking') {
          accThinking += evt.text ?? '';
          patchAssistant({ thinking: accThinking });
        } else if (evt.type === 'content') {
          accContent += evt.text ?? '';
          patchAssistant({ content: accContent });
        } else if (evt.type === 'done') {
          patchAssistant({
            sources: evt.sources,
            retrievalMode: evt.retrievalMode,
            refusal: evt.refusal,
          });
          if (!accContent && evt.content) patchAssistant({ content: evt.content });
        } else if (evt.type === 'error') {
          patchAssistant({ content: evt.text || '抱歉，服务出错了，请稍后重试。' });
        }
      });
    } catch {
      patchAssistant({ content: accContent || '抱歉，服务出错了，请稍后重试。' });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
      {/* Conversation list (always visible) */}
      <div style={{
        width: 208,
        flexShrink: 0,
        borderRight: '1px solid #f0f0f0',
        display: 'flex',
        flexDirection: 'column',
        padding: '12px 8px',
        overflowY: 'auto',
      }}>
        <Button icon={<PlusOutlined />} onClick={handleNewSession} block style={{ marginBottom: 12 }}>
          新建对话
        </Button>
        {sessions.map(s => {
          const isActive = s.id === activeId;
          const isHover = s.id === hoverId || isActive;
          const isEditing = editingId === s.id;
          return (
            <div
              key={s.id}
              onClick={() => { if (!isEditing) setActiveId(s.id); }}
              onMouseEnter={() => setHoverId(s.id)}
              onMouseLeave={() => setHoverId(null)}
              style={{
                padding: '9px 12px',
                borderRadius: 8,
                cursor: 'pointer',
                marginBottom: 4,
                fontSize: 14,
                display: 'flex',
                alignItems: 'center',
                background: isActive ? '#e6f4ff' : (isHover ? '#f5f5f5' : 'transparent'),
                color: isActive ? '#1677ff' : 'rgba(0,0,0,0.85)',
              }}
            >
              {isEditing ? (
                <Input
                  size="small"
                  value={editingTitle}
                  autoFocus
                  onChange={e => setEditingTitle(e.target.value)}
                  onPressEnter={commitRename}
                  onBlur={commitRename}
                  onClick={e => e.stopPropagation()}
                />
              ) : (
                <>
                  <span style={{ flex: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {s.title}
                  </span>
                  {isHover && (
                    <Space size={2} style={{ marginLeft: 8 }} onClick={e => e.stopPropagation()}>
                      <Button
                        type="text"
                        size="small"
                        icon={<EditOutlined />}
                        onClick={() => startRename(s)}
                      />
                      <Button
                        type="text"
                        size="small"
                        danger
                        icon={<DeleteOutlined />}
                        onClick={() => handleDelete(s.id)}
                      />
                    </Space>
                  )}
                </>
              )}
            </div>
          );
        })}
      </div>

      {/* Chat area */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        <div ref={messagesContainerRef} style={{ flex: 1, overflowY: 'auto', padding: '0 12px', marginBottom: 12 }}>
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
              <Space align="start" style={{ maxWidth: '90%' }}>
                {msg.role === 'assistant' && <RobotOutlined style={{ color: '#1677ff' }} />}
                <div style={{
                  maxWidth: 720,
                  minWidth: 0,
                  padding: '10px 14px',
                  borderRadius: 12,
                  background: msg.role === 'user' ? '#1677ff' : '#f0f0f0',
                  color: msg.role === 'user' ? '#fff' : '#333',
                  wordBreak: 'break-word',
                }}>
                  {msg.role === 'assistant' ? (
                    <div className="markdown-body">
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>{msg.content}</ReactMarkdown>
                    </div>
                  ) : (
                    <div style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{msg.content}</div>
                  )}
                  {msg.thinking && (
                    <details open style={{ marginTop: 8, fontSize: 12 }}>
                      <summary style={{ cursor: 'pointer', color: '#8c8c8c', userSelect: 'none' }}>思考过程</summary>
                      <div style={{
                        marginTop: 6,
                        padding: '8px 10px',
                        background: 'rgba(0,0,0,0.04)',
                        borderRadius: 8,
                        whiteSpace: 'pre-wrap',
                        overflowWrap: 'anywhere',
                        color: '#666',
                      }}>{msg.thinking}</div>
                    </details>
                  )}
                  {(msg.retrievalMode || msg.refusal) && (
                    <div style={{ marginTop: 6 }}>
                      {msg.retrievalMode && <Tag color="green" style={{ marginInlineEnd: 4 }}>模式: {msg.retrievalMode}</Tag>}
                      {msg.refusal && <Tag color="orange">拒答</Tag>}
                    </div>
                  )}
                  {msg.sources && msg.sources.length > 0 && (
                    <div style={{ marginTop: 8, paddingTop: 6, borderTop: '1px solid rgba(0,0,0,0.06)' }}>
                      <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>来源</Text>
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                        {msg.sources.map((s, i) => (
                          <Tag key={i} color="blue" style={{ fontSize: 11, margin: 0 }}>{s.fileName}</Tag>
                        ))}
                      </div>
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

        <div style={{ display: 'flex', gap: 8, paddingRight: 12 }}>
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
    </div>
  );
};

export default ChatPanel;
