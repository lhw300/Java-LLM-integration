# LCallAI — Enterprise Intelligent Call Center AI Engine

A Java-based enterprise-grade intelligent customer service platform that integrates **Retrieval-Augmented Generation (RAG)**, **multi-turn dialogue management**, **intent recognition**, and **voice processing (ASR/TTS)** into a unified system. Supports full cloud, fully local, and hybrid deployment modes.

---

## Key Features

- **Multi-mode RAG Engine**: Two-stage retrieval (vector coarse ranking + LLM reranking) with semantic fast-track and async parallel rerank
- **Intelligent Intent Routing**: Automatically classifies user input into 7 intent types — GREETING / QUERY / COMMAND / FEEDBACK / CHITCHAT / INFORM / ACK — and dispatches to the corresponding handler
- **Multi-turn Dialogue Management**: `ChatSession` maintains full conversation history with cross-turn entity inheritance, anaphora resolution, and entity correction
- **Flexible Model Routing**: `ModelRouter` routes Rewrite, Rerank, and FinalAsk to different models, balancing cost and quality
- **Dual Storage Backend**: PostgreSQL + pgvector (online) / Apache Lucene (offline) dual-engine support
- **Full Voice Pipeline**: Integrates Alibaba Cloud NLS and Baidu ASR with PCM/WAV streaming recognition, plus Ekho TTS synthesis
- **Call Center Integration**: Compliant with the LCall IVR protocol — drives dynamic TTS playback and flow transitions via HTTP

---

## Tech Stack

| Layer | Component |
|-------|-----------|
| Language | Java 11+ |
| Build | Maven (`pom.xml`) |
| LLM | Alibaba Cloud Qwen-Turbo / Qwen-Plus (OpenAI-compatible), local Ollama |
| Embedding | Alibaba Cloud `text-embedding-v3` / local DJL + text2vec-base-chinese |
| Reranking | LLM-based rerank (score-to-distance conversion) |
| Vector DB | PostgreSQL + pgvector |
| Full-text Search | Apache Lucene |
| ASR TTS | Alibaba Cloud NLS WebSocket, Baidu ASR HTTP |
| HTTP Client | OkHttp |
| JSON | Jackson / org.json |

---

## Project Structure

```
src/main/java/com/lcallai/
├── SessionManager.java        # Global initialization & model router assembly (qwen / ollama / hybrid)
├── ChatSession.java           # Session core: multi-turn dialogue, two-stage retrieval, intent dispatch
├── SearchService.java         # Knowledge retrieval service (pgvector / Lucene dual backend)
├── IngestionService.java      # Knowledge ingestion: TXT/XLSX parsing → embedding → DB write
├── ModelRouter.java           # Three-stage model routing (Rewrite → Rerank → FinalAsk)
├── intent/                    # Intent recognition (IntentClassifier / IntentResult)
├── handler/                   # Intent handlers (QueryHandler / ChitchatHandler / ...)
├── ChatVisaExample.java       # RAG multi-turn dialogue scenario test (visa business)
├── ChatIntentExample.java     # Full-scenario intent recognition stress test

```

---

## Getting Started

### 1. Prerequisites

- Java 11+, Maven 3.6+
- PostgreSQL 14+ with pgvector extension (or skip — use Lucene offline mode only)
- (Optional) Ollama local service for hybrid mode

### 2. Configuration

Create `aiconfig.properties` in the project root or your specified `baseDir`:

```properties
# LLM type: qwen / ollama / hybrid
ai.type=qwen

# Alibaba Cloud API Key
api.key.qwen=your_dashscope_key

# Storage backend: online (pgvector) or lucene (local)
storage.type=online

# PostgreSQL connection (online mode)
db.postgres.url=jdbc:postgresql://localhost:5432/your_db
db.postgres.user=your_user
db.postgres.password=your_password
db.postgres.table.online=enterprise_knowledge_qwen_1024

# Lucene index path (lucene mode)
lucene.index.path=e:/ai/lucene_index

# Local embedding model path (hybrid / lucene mode)
embed.model.path=e:/ai/text2vec-base-chinese-pt
rerank.model.path=e:/ai/rerank-model
```

### 3. Ingest Knowledge Base

Prepare a TXT file with entries in `Category | Summary | Content` format, then run:

```bash
mvn compile exec:java -Dexec.mainClass="com.lcallai.IngestionService" \
  -Dexec.args="e:/ai"
```

### 4. Run Dialogue Test

```bash

mvn compile exec:java -Dexec.mainClass="com.lcallai.IntentTestRunner"
mvn compile exec:java -Dexec.mainClass="com.lcallai.RagTestRunner"
mvn compile exec:java -Dexec.mainClass="com.lcallai.ChatVisaExample"
```

---

## Core Architecture

```
User Input
    │
    ▼
IntentClassifier (intent recognition)
    │
    ├─ QUERY ───► ChatSession.ask()
    │               ├─ Query Rewrite (Rewrite LLM)
    │               ├─ Vector Retrieval (pgvector / Lucene)
    │               ├─ Semantic Rerank (Rerank LLM, parallel)
    │               └─ Final Answer (FinalAsk LLM)
    │
    ├─ COMMAND ──► CommandHandler (transfer / volume / replay)
    ├─ CHITCHAT ──► ChitchatHandler (small talk with business steering)
    ├─ FEEDBACK ──► FeedbackHandler (sentiment analysis, negative counter)
    ├─ GREETING ──► GreetingHandler
    ├─ INFORM ───► InformHandler (entity extraction & archiving)
    └─ ACK ──────► AckHandler
```

### Two-Stage Retrieval Strategy

| Stage | Method | Description |
|-------|--------|-------------|
| Coarse Rank | Vector cosine distance | Recall Top-N candidates from the knowledge base |
| Fast Track | distance < 0.25 | Direct hit — skip reranking |
| Fine Rank | LLM Rerank scoring | Parallel semantic distance computation with rescue compensation |
| Reject | distance > 0.82 | No relevant content found — return fallback response |

 

---

## Deployment Modes

| Mode | Rewrite | Rerank | FinalAsk | Embed | Best For |
|------|---------|--------|----------|-------|---------|
| `qwen` 	|  Qwen-Plus | Qwen-Turbo | Full cloud — best quality |
| `ollama` | Local Ollama | Local Ollama | Local Ollama | Fully offline — data stays on-premise |
| `hybrid` | Qwen-Turbo | DJL local | Qwen-Plus | Balanced cost and quality |

---

## Developer Notes

- **Add a new intent handler**: Implement the `IntentHandler` interface and `register()` it in `SessionManager`
- **Incremental knowledge update**: Re-run `IngestionService`; the vector table supports incremental writes
- **Performance debugging**: `SessionManager.warmUp()` prints full end-to-end latency for each pipeline stage

---

## License

This project is an internal enterprise application for internal use only.
