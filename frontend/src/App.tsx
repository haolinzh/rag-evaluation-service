import React, { useState, useEffect } from 'react';
import { Layout, theme, Grid, Button, Drawer } from 'antd';
import { FileTextOutlined, BarChartOutlined, SettingOutlined } from '@ant-design/icons';
import { Group, Panel, Separator } from 'react-resizable-panels';
import DocumentPanel from './components/DocumentPanel';
import ChatPanel from './components/ChatPanel';
import MetricsPanel from './components/MetricsPanel';
import LogPanel from './components/LogPanel';
import DocumentManagement from './components/DocumentManagement';
import LogManagement from './components/LogManagement';
import ConfigPage from './components/ConfigPage';
import type { DocumentMeta } from './types';
import { listDocuments } from './api';

const { Header, Content } = Layout;

const App: React.FC = () => {
  const [documents, setDocuments] = useState<DocumentMeta[]>([]);
  const [retrievalMode, setRetrievalMode] = useState<string>('hybrid');
  const [view, setView] = useState<'main' | 'documents' | 'logs' | 'config'>('main');
  const [docsOpen, setDocsOpen] = useState(false);
  const [metricsOpen, setMetricsOpen] = useState(false);
  const { token: { colorBgContainer } } = theme.useToken();
  const screens = Grid.useBreakpoint();
  const isMobile = !screens.md;

  const refreshDocuments = () => {
    listDocuments().then(setDocuments).catch(console.error);
  };

  useEffect(() => { refreshDocuments(); }, []);

  if (view === 'documents') {
    return <DocumentManagement onBack={() => setView('main')} />;
  }

  if (view === 'logs') {
    return <LogManagement onBack={() => setView('main')} />;
  }

  if (view === 'config') {
    return <ConfigPage onBack={() => setView('main')} />;
  }

  const documentPanel = (
    <DocumentPanel
      documents={documents}
      retrievalMode={retrievalMode}
      onModeChange={setRetrievalMode}
      onRefresh={refreshDocuments}
      onOpenManagement={() => setView('documents')}
    />
  );
  const chatPanel = <ChatPanel retrievalMode={retrievalMode} />;
  const metricsPanel = (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', gap: 16, overflowY: 'auto' }}>
      <MetricsPanel />
      <LogPanel onOpenManagement={() => setView('logs')} />
    </div>
  );

  return (
    <Layout style={{ height: '100vh', overflow: 'hidden' }}>
      <style>{`
        .rag-sep { width: 6px; background: #f0f0f0; transition: background 0.15s ease; }
        .rag-sep[data-separator="hover"], .rag-sep[data-separator="active"] { background: #1677ff; }
        .rag-sep-v { height: 6px; background: #f0f0f0; transition: background 0.15s ease; }
        .rag-sep-v[data-separator="hover"], .rag-sep-v[data-separator="active"] { background: #1677ff; }
      `}</style>

      <Header style={{
        color: '#fff', fontSize: 20, fontWeight: 600,
        display: 'flex', alignItems: 'center', padding: '0 24px', gap: 12,
      }}>
        {isMobile && (
          <Button type="text" icon={<FileTextOutlined />} onClick={() => setDocsOpen(true)} style={{ color: '#fff' }}>
            文档
          </Button>
        )}
        <span style={{ flex: 1, textAlign: isMobile ? 'center' : 'left' }}>RAG 知识库问答系统</span>
        <Button type="text" icon={<SettingOutlined />} onClick={() => setView('config')} style={{ color: '#fff' }}>
          配置
        </Button>
        {isMobile && (
          <Button type="text" icon={<BarChartOutlined />} onClick={() => setMetricsOpen(true)} style={{ color: '#fff' }}>
            指标
          </Button>
        )}
      </Header>

      {isMobile ? (
        <Content style={{ padding: 16, display: 'flex', flexDirection: 'column', minHeight: 0, flex: 1, overflow: 'hidden' }}>
          {chatPanel}
          <Drawer title="文档" placement="left" width={320} open={docsOpen} onClose={() => setDocsOpen(false)}>
            {documentPanel}
          </Drawer>
          <Drawer title="运维指标" placement="right" width={320} open={metricsOpen} onClose={() => setMetricsOpen(false)}>
            {metricsPanel}
          </Drawer>
        </Content>
      ) : (
        <Group orientation="horizontal" id="rag-main" style={{ flex: 1, minHeight: 0, overflow: 'hidden' }}>
          <Panel id="documents" defaultSize="20" minSize="15" maxSize="35" style={{ padding: 16, background: colorBgContainer, display: 'flex', flexDirection: 'column' }}>
            {documentPanel}
          </Panel>
          <Separator className="rag-sep" />
          <Panel id="chat" defaultSize="56" minSize="30" style={{ padding: 16, display: 'flex', flexDirection: 'column' }}>
            {chatPanel}
          </Panel>
          <Separator className="rag-sep" />
          <Panel id="metrics" defaultSize="24" minSize="18" maxSize="32" style={{ padding: 16, background: colorBgContainer, display: 'flex', flexDirection: 'column' }}>
            {metricsPanel}
          </Panel>
        </Group>
      )}
    </Layout>
  );
};

export default App;
