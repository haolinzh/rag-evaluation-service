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
  thinking?: string | null;
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
  thinking?: string | null;
  retrievalMode?: string | null;
  refusal?: boolean | null;
  sources?: string | null;
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
  retrievedChunks: string | null;
  rerankCandidates: string | null;
  prompt: string | null;
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

export interface EvaluationQuestion {
  id: string;
  question: string;
  language: string;
  expectedType: string;
  difficulty: string;
}

export interface EvaluationQuestionResult {
  questionId: string;
  question: string;
  language: string;
  expectedType: string;
  answer: string;
  retrievalMode: string;
  refusal: boolean;
  sources: Source[];
  latencyMs: number;
  faithfulness: number;
  contextPrecision: number;
  answerCompliance: number;
  refusalAppropriate: number;
  styleConsistent: number;
  error?: string;
}

export interface EvaluationSummary {
  mode: string;
  totalQuestions: number;
  answeredQuestions: number;
  avgFaithfulness: number;
  avgContextPrecision: number;
  avgAnswerCompliance: number;
  avgRefusalAppropriate: number;
  avgStyleConsistent: number;
  p50LatencyMs: number;
  p95LatencyMs: number;
  avgLatencyMs: number;
}

export interface EvaluationReport {
  modes: string[];
  summaries: EvaluationSummary[];
  results: Record<string, EvaluationQuestionResult[]>;
}

export type EvaluationEvent =
  | { type: 'ingest_start'; missing: string[]; total: number }
  | { type: 'ingesting'; fileName: string }
  | { type: 'ingested'; fileName: string; chunks: number }
  | { type: 'ingest_error'; fileName: string; message: string }
  | { type: 'ingest_done'; ingested: number; failed: string[]; skipped?: number }
  | { type: 'start'; modes: string[]; totalQuestions: number }
  | { type: 'mode_start'; mode: string; index: number; totalModes: number }
  | { type: 'question_start'; mode: string; questionId: string; index: number; total: number; question: string }
  | { type: 'question_done'; mode: string; questionId: string; index: number; total: number; result: EvaluationQuestionResult }
  | { type: 'question_error'; mode: string; questionId: string; index: number; total: number; question: string; message: string }
  | { type: 'mode_done'; mode: string; summary: EvaluationSummary }
  | { type: 'done'; report: EvaluationReport }
  | { type: 'error'; message: string };
