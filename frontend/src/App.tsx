import React, { useState, useEffect } from 'react';
import { Layout, theme } from 'antd';
import DocumentPanel from './components/DocumentPanel';
import ChatPanel from './components/ChatPanel';
import MetricsPanel from './components/MetricsPanel';
import type { DocumentMeta } from './types';
import { listDocuments } from './api';

const { Header, Sider, Content } = Layout;

const App: React.FC = () => {
  const [documents, setDocuments] = useState<DocumentMeta[]>([]);
  const [retrievalMode, setRetrievalMode] = useState<string>('hybrid');
  const { token: { colorBgContainer } } = theme.useToken();

  const refreshDocuments = () => {
    listDocuments().then(setDocuments).catch(console.error);
  };

  useEffect(() => { refreshDocuments(); }, []);

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{
        color: '#fff', fontSize: 20, fontWeight: 600,
        display: 'flex', alignItems: 'center', padding: '0 24px',
      }}>
        RAG 知识库问答系统
      </Header>
      <Layout>
        <Sider width={360} style={{ background: colorBgContainer, padding: 16, overflowY: 'auto' }}>
          <DocumentPanel
            documents={documents}
            retrievalMode={retrievalMode}
            onModeChange={setRetrievalMode}
            onRefresh={refreshDocuments}
          />
        </Sider>
        <Content style={{ padding: 16, display: 'flex', flexDirection: 'column' }}>
          <ChatPanel retrievalMode={retrievalMode} />
        </Content>
        <Sider width={320} style={{ background: colorBgContainer, padding: 16, overflowY: 'auto' }}>
          <MetricsPanel />
        </Sider>
      </Layout>
    </Layout>
  );
};

export default App;
