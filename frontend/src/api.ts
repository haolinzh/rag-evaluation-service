import axios from 'axios';
import type { DocumentMeta, ChatResponse, ChatMessage, OpsReport, ChunkConfig, ChunkPreview, RequestLog, SystemConfig, Source, EvaluationQuestion, EvaluationEvent, EvaluationReport, EvaluationRunMeta } from './types';

const api = axios.create({ baseURL: '/api' });

export async function uploadDocument(
  file: File,
  config: ChunkConfig,
  onProgress?: (percent: number) => void
): Promise<DocumentMeta> {
  const form = new FormData();
  form.append('file', file);
  form.append('splitMode', config.splitMode);
  form.append('chunkSize', String(config.chunkSize));
  form.append('delimiter', config.delimiter);
  form.append('overlap', String(config.overlap));
  const { data } = await api.post('/documents/upload', form, {
    onUploadProgress: (e) => {
      if (onProgress && e.total) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    },
  });
  return data;
}

export async function listDocuments(): Promise<DocumentMeta[]> {
  const { data } = await api.get('/documents');
  return data;
}

export async function deleteDocument(id: number): Promise<void> {
  await api.delete(`/documents/${id}`);
}

export function downloadDocument(id: number): void {
  const a = document.createElement('a');
  a.href = `/api/documents/${id}/download`;
  a.download = '';
  document.body.appendChild(a);
  a.click();
  a.remove();
}

export async function getDocumentChunks(id: number): Promise<ChunkPreview[]> {
  const { data } = await api.get(`/documents/${id}/chunks`);
  return data;
}

export async function askQuestion(question: string, sessionId: string, mode: string): Promise<ChatResponse> {
  const { data } = await api.post('/chat', { question, sessionId, mode });
  return data;
}

export interface StreamEvent {
  type: 'thinking' | 'content' | 'done' | 'error';
  text?: string;
  content?: string;
  thinking?: string;
  retrievalMode?: string;
  sources?: Source[];
  refusal?: boolean;
  refusalReason?: string;
}

export async function streamAsk(
  question: string,
  sessionId: string,
  mode: string,
  onEvent: (evt: StreamEvent) => void
): Promise<void> {
  const resp = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question, sessionId, mode }),
  });

  if (!resp.ok || !resp.body) {
    const text = await resp.text().catch(() => '');
    throw new Error(text || `HTTP ${resp.status}`);
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const rawEvent = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      for (const line of rawEvent.split('\n')) {
        if (!line.startsWith('data:')) continue;
        const data = line.slice(5).trim();
        if (!data) continue;
        try {
          onEvent(JSON.parse(data) as StreamEvent);
        } catch {
          // ignore malformed chunk
        }
      }
    }
  }
}

export async function getChatHistory(sessionId: string): Promise<ChatMessage[]> {
  const { data } = await api.get(`/chat/history/${sessionId}`);
  return data;
}

export async function deleteChatHistory(sessionId: string): Promise<void> {
  await api.delete(`/chat/history/${sessionId}`);
}

export async function fetchReport(): Promise<Blob> {
  const { data } = await api.get('/report/csv', { responseType: 'blob' });
  return data;
}

export async function fetchMetricsSummary(): Promise<OpsReport> {
  const { data } = await api.get('/report/summary');
  return data;
}

export async function clearCache(): Promise<void> {
  await api.post('/cache/clear');
}

export async function fetchLogs(limit = 100): Promise<RequestLog[]> {
  const { data } = await api.get('/logs', { params: { limit } });
  return data;
}

export async function clearLogs(): Promise<void> {
  await api.delete('/logs');
}

export async function fetchConfig(): Promise<SystemConfig> {
  const { data } = await api.get('/config');
  return data;
}

export async function updateConfig(config: SystemConfig): Promise<SystemConfig> {
  const { data } = await api.put('/config', config);
  return data;
}

export async function updateRetrievalMode(mode: string): Promise<SystemConfig> {
  const { data } = await api.put('/config/mode', { mode });
  return data;
}

export async function fetchEvaluationQuestions(): Promise<EvaluationQuestion[]> {
  const { data } = await api.get('/evaluation/questions');
  return data;
}

export async function fetchEvaluationHistory(): Promise<EvaluationRunMeta[]> {
  const { data } = await api.get('/evaluation/history');
  return data;
}

export async function fetchEvaluationRun(id: number): Promise<EvaluationReport> {
  const { data } = await api.get(`/evaluation/history/${id}`);
  return data;
}

export async function runEvaluation(
  modes: string[],
  clearCache: boolean,
  onEvent: (evt: EvaluationEvent) => void
): Promise<void> {
  const resp = await fetch('/api/evaluation/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ modes, clearCache }),
  });

  if (!resp.ok || !resp.body) {
    const text = await resp.text().catch(() => '');
    throw new Error(text || `HTTP ${resp.status}`);
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const rawEvent = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      for (const line of rawEvent.split('\n')) {
        if (!line.startsWith('data:')) continue;
        const data = line.slice(5).trim();
        if (!data) continue;
        try {
          onEvent(JSON.parse(data) as EvaluationEvent);
        } catch {
          // ignore malformed chunk
        }
      }
    }
  }
}
