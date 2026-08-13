import axios from 'axios';
import type { DocumentMeta, ChatResponse, ChatMessage } from './types';

const api = axios.create({ baseURL: '/api' });

export async function uploadDocument(
  file: File,
  onProgress?: (percent: number) => void
): Promise<DocumentMeta> {
  const form = new FormData();
  form.append('file', file);
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

export async function askQuestion(question: string, sessionId: string): Promise<ChatResponse> {
  const { data } = await api.post('/chat', { question, sessionId });
  return data;
}

export async function getChatHistory(sessionId: string): Promise<ChatMessage[]> {
  const { data } = await api.get(`/chat/history/${sessionId}`);
  return data;
}

export async function fetchReport(): Promise<Blob> {
  const { data } = await api.get('/report/csv', { responseType: 'blob' });
  return data;
}
