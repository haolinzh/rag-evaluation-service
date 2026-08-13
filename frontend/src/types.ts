export interface DocumentMeta {
  id: number;
  fileName: string;
  fileSize: number;
  chunkCount: number;
  createdAt: string;
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
  totalTokens: number;
  cacheHitRate: number;
  refusalRate: number;
  complianceRate: number;
}
