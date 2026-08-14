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
  answerComplianceRate: number;
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
  keywordCount: number;
  vectorCount: number;
  overlapCount: number;
  embeddingLatencyMs: number;
  keywordLatencyMs: number;
  vectorLatencyMs: number;
  rerankLatencyMs: number;
  cacheLookupLatencyMs: number;
  status: string;
}

export interface ModelOption {
  group: 'chat' | 'embedding' | 'rerank';
  id: string;
  label: string;
  dimensions: number | null;
}

export interface SystemConfig {
  retrieval: {
    mode: string;
    topK: number;
    recallSizeMultiplier: number;
    rrfK: number;
    rerankCandidates: number;
    rerankEnabled: boolean;
    similarityThreshold: number;
  };
  models: { chat: string; embedding: string; rerank: string };
  safety: {
    minSimilarity: number;
    enableOutOfScopeCheck: boolean;
    outOfScopeThreshold: number;
    forbiddenKeywords: string;
  };
  cache: { enabled: boolean; ttlSeconds: number };
  modelOptions: ModelOption[];
  embeddingDimension: number;
}
