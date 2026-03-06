# Soloplay

An AI-powered solo tabletop RPG assistant,
built as a conference demo for progressive disclosure of LLM and RAG integration concepts.

Built with **Quarkus**, **LangChain4j**, **Ollama** (local LLM), and **Neo4j** (graph database + vector storage).

---

## What This Demonstrates

This application shows four levels of LLM integration, each building on the last:

### Level 1 — Plain Chat (`/chat`)

A single `@RegisterAiService` interface with an inline system prompt. No memory, no tools —
the simplest possible LangChain4j integration.

**Key source:**

- [`ChatAssistant`](src/main/java/dev/ebullient/soloplay/ai/ChatAssistant.java) — the AI service interface
- [`ChatResource`](src/main/java/dev/ebullient/soloplay/api/ChatResource.java) — REST endpoint at `/api/chat`

### Level 2 — Guided Character Creation (`/play/{gameId}`)

Session memory via `@MemoryId`, structured JSON output with guardrails, and a multi-turn
conversation that extracts character details across several stages.

**Key source:**

- [`ActorCreationAssistant`](src/main/java/dev/ebullient/soloplay/play/ActorCreationAssistant.java) — AI service with session memory and structured extraction
- [`ActorCreationEngine`](src/main/java/dev/ebullient/soloplay/play/ActorCreationEngine.java) — orchestrates the multi-stage creation flow

### Level 3 — Lore Queries with RAG (`/lore`)

Retrieval-augmented generation: vector similarity search against ingested campaign documents,
with AI tools for cross-reference resolution.

The document ingestion pipeline (`/ingest`) prepares the knowledge base: upload markdown
with YAML frontmatter → chunk → embed with nomic-embed-text → store in Neo4j vector index.

**Key source:**

- [`LoreAssistant`](src/main/java/dev/ebullient/soloplay/ai/LoreAssistant.java) — RAG-enabled AI service
- [`LoreRetriever`](src/main/java/dev/ebullient/soloplay/ai/LoreRetriever.java) — `RetrievalAugmentor` supplier for Neo4j vector search
- [`LoreTools`](src/main/java/dev/ebullient/soloplay/ai/LoreTools.java) — AI tools: document retrieval and semantic search
- [`IngestService`](src/main/java/dev/ebullient/soloplay/IngestService.java) — document parsing, chunking, and embedding pipeline

### Level 4 — Full Gameplay (`/play/{gameId}`)

Full orchestration via an `AgentOrchestrator` that coordinates five specialized agents per turn.
Only the narration agent maintains session memory and has tool access; the other four are stateless
and run concurrently via `CompletableFuture`. A WebSocket transport streams results for interactive play.

The five agents:

| Agent | Purpose | Stateful? |
| ----- | ------- | --------- |
| [`NarrationAgent`](src/main/java/dev/ebullient/soloplay/play/agents/NarrationAgent.java) | Storytelling — produces the scene narration | Yes (session memory + tools) |
| [`DiceAgent`](src/main/java/dev/ebullient/soloplay/play/agents/DiceAgent.java) | Decides if/what dice roll is required | No |
| [`SuggestionAgent`](src/main/java/dev/ebullient/soloplay/play/agents/SuggestionAgent.java) | Generates 2–3 concrete next-action choices | No |
| [`CheckpointAgent`](src/main/java/dev/ebullient/soloplay/play/agents/CheckpointAgent.java) | Identifies milestone moments and segment completion | No |
| [`RecapAgent`](src/main/java/dev/ebullient/soloplay/play/agents/RecapAgent.java) | Produces a 1–2 sentence turn summary | No |

**Key source:**

- [`AgentOrchestrator`](src/main/java/dev/ebullient/soloplay/play/agents/AgentOrchestrator.java) — coordinates agents, assembles the composite `GamePlayResponse`
- [`GamePlayEngine`](src/main/java/dev/ebullient/soloplay/play/GamePlayEngine.java) — routes turns through scene start, recap, action, and roll resolution
- [`GameTools`](src/main/java/dev/ebullient/soloplay/play/GameTools.java) — AI tools: story recap, actor lookup, location lookup
- [`PlayWebSocket`](src/main/java/dev/ebullient/soloplay/play/PlayWebSocket.java) — WebSocket at `/ws/play/{gameId}` for interactive play

---

## Application Routes

| Route | Description |
| ----- | ----------- |
| `/` | Landing page — links to all sections |
| `/chat` | Level 1: Plain chat with the LLM |
| `/lore` | Level 3: Lore queries with RAG |
| `/ingest` | Document upload and ingestion management |
| `/game` | Game list; create new games |
| `/play/{gameId}` | Levels 2 + 4: Character creation → active gameplay |
| `/inspect/{gameId}` | View game state and turn history |
| `/party/{gameId}` | Manage party members |

---

## Running the Demo

### Prerequisites

**Ollama** (local LLM):

```shell
ollama serve
ollama pull llama3.2           # default chat model
ollama pull nomic-embed-text   # embeddings
```

The default model (`llama3.2`) works for levels 1 and 2. For tool calling at levels 3 and 4,
a larger model is recommended. Override via `.env` in the project root:

```shell
QUARKUS_LANGCHAIN4J_OLLAMA_CHAT_MODEL_MODEL_NAME=qwen3:14b
```

**Neo4j** (graph database + vector store):

```shell
docker compose up -d neo4j
```

### Start the application

```shell
./mvnw quarkus:dev
```

Open [http://localhost:8080](http://localhost:8080).

---

## Frontend: Renarde + Vanilla JS

The UI is server-rendered with [Renarde](https://docs.quarkiverse.io/quarkus-renarde/dev/index.html)
(Qute templates, type-safe routing, flash scope) and driven entirely by vanilla JavaScript — no
frontend framework, no build step.

**Renarde controllers** handle page routing, form submissions, and redirects:

- [`Game`](src/main/java/dev/ebullient/soloplay/web/Game.java) — CRUD forms with flash-scope feedback
- [`Play`](src/main/java/dev/ebullient/soloplay/web/Play.java) — serves the play page with a `data-game-id` attribute for JS to pick up
- [`Lore`](src/main/java/dev/ebullient/soloplay/web/Lore.java) — multipart file upload for document ingestion (CSRF-protected with `{#authenticityToken/}`)

**Vanilla JS modules** add client-side interactivity:

| Module | What it does |
| ------ | ------------ |
| [`play-interface.js`](src/main/resources/META-INF/resources/play-interface.js) | WebSocket streaming chat — reconnection, message buffering, choice buttons |
| [`chat-interface.js`](src/main/resources/META-INF/resources/chat-interface.js) | Simple fetch-based chat for `/chat` and `/lore` |
| [`game-index.js`](src/main/resources/META-INF/resources/game-index.js) | Game list — info/delete modals via REST calls |
| [`inspect.js`](src/main/resources/META-INF/resources/inspect.js) | Developer tool: browse game-state JSON from `/api/game` endpoints |

The REST API under `/api/` ([`GameResource`](src/main/java/dev/ebullient/soloplay/api/GameResource.java),
[`ChatResource`](src/main/java/dev/ebullient/soloplay/api/ChatResource.java),
[`LoreResource`](src/main/java/dev/ebullient/soloplay/api/LoreResource.java)) provides
the JSON and HTML-fragment responses consumed by these scripts.

---

## Presentations

* [Devnexus 2026 (Erin Schnabel and Jennifer Reif)](https://speakerdeck.com/jmhreif/supercharge-your-applications-with-java-graphs-and-a-touch-of-ai)

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for architecture details, API reference,
build commands, and contributor guidelines.
