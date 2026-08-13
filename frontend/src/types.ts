export interface DocumentMeta {
  id: number;
  fileName: string;
  fileSize: number;
  chunkCount: number;
  createdAt: string;
  updatedAt?: string;
  splitMode?: string;
  chunkSize?: number;
  overlap?: number;
  delimiter?: string;
}

export interface ChunkPreview {
  chunkIndex: number;
  chapter: string | null;
  section: string | null;
  snippet: string;
}

export interface ChunkConfig {
  splitMode: 'size' | 'delimiter';
  chunkSize: number;
  delimiter: string;
  overlap: number;
}

export interface Source {
  fileName: string;
  snippet: string;
  score: number;
  sourceType: string;
}

export interface ChatResponse {
  content: string;
  retrievalMode: string;
  sources: Source[];
  refusal: boolean;
  refusalReason: string | null;
}

export interface ChatMessage {
  id: number;
  sessionId: string;
  role: string;
  content: string;
  createdAt: string;
}

export interface OpsReport {
  totalRequests: number;
  p50LatencyMs: number;
  p95LatencyMs: number;
  missP50LatencyMs: number;
  missP95LatencyMs: number;
  totalTokens: number;
  cacheHitRate: number;
  refusalRate: number;
}

export interface RequestLog {
  id: number;
  requestId: string;
  sessionId: string;
  createdAt: string;
  question: string;
  answer: string | null;
  model: string;
  retrievalMode: string;
  hitDocuments: string;
  responseTimeMs: number;
  llmCallCount: number;
  cacheHit: boolean;
  refusal: boolean;
  refusalReason: string | null;
  retrievalLatencyMs: number;
  generationLatencyMs: number;
  promptTokens: number;
  completionTokens: number;
  chunksRetrieved: number;
  maxChunkScore: number;
  piiRedactions: number;
  status: string;
}
