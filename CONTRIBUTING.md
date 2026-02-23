# Contributing to Soloplay

Thanks for your interest in contributing! This guide covers the technical architecture, development setup, and coding patterns you'll need to work on Soloplay.

## Project Overview

Soloplay is an AI-powered assistant for solo tabletop RPG gameplay that combines:

- Game state management (games, actors, locations, events, plot flags) in a graph database
- RAG (Retrieval Augmented Generation) queries against campaign/adventure documents
- AI tools that can autonomously query and update game state
- Local LLM inference (no cloud dependencies)

**Tech Stack:**

- **Java 21** - Modern Java features (records, pattern matching, etc.)
- **Quarkus 3.30.6** - Supersonic Subatomic Java framework with Quarkus REST
- **LangChain4j** - AI service orchestration with declarative interfaces
- **Ollama** - Local LLM inference (default chat model: llama3.2; embeddings: nomic-embed-text)
- **Neo4j** - Graph database for game state and vector embeddings (768 dimensions)
- **Quarkus WebSockets Next** - Streaming gameplay UI via WebSocket
- **Renarde MVC** - Type-safe server-side rendering
- **Qute** - Templating engine
- **CommonMark** - Markdown to HTML conversion

## Build Commands

### Development

```bash
# Run in dev mode with live reload
./mvnw quarkus:dev

# Dev UI available at http://localhost:8080/q/dev/
```

### Testing

```bash
# Run unit tests
./mvnw test

# Run integration tests
./mvnw verify

# Run a single test class
./mvnw test -Dtest=ClassName
```

### Building

```bash
# Standard package
./mvnw package

# Build uber-jar
./mvnw package -Dquarkus.package.jar.type=uber-jar

# Native executable (requires GraalVM)
./mvnw package -Dnative

# Native build in container
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

## Local Development Setup

### Prerequisites

You'll need the following running locally:

1. **Ollama** with required models:

   ```bash
   # Install Ollama from https://ollama.ai

   # Pull required models
   ollama pull llama3.2
   ollama pull nomic-embed-text
   ```

2. **Neo4j** database:

   ```bash
   # Using Docker Compose
   docker compose -f compose.yaml up -d
   ```

   Optional but recommended: apply indexes and constraints from `src/main/resources/neo4j-indexes.cypher` (see `NEO4J_SETUP.md`).

### Configuration

The application expects these services at default locations:

- Ollama: Configure via `quarkus.langchain4j.ollama.base-url` in application.properties
- Neo4j: Configure via standard Quarkus Neo4j properties

See [src/main/resources/application.properties](src/main/resources/application.properties) for configuration options.

## Architecture Overview

### AI Services Pattern

The application uses LangChain4j's declarative AI service interfaces:

- **ChatAssistant**: Simple chat interface - methods are auto-implemented by LangChain4j
- **LoreAssistant**: RAG-enabled queries - automatically retrieves relevant embeddings
- **ActorCreationAssistant**: Character creation assistant (RAG-enabled, structured JSON output)
- **GamePlayAssistant**: GM assistant for play turns (RAG + tools, structured JSON output)

These are plain Java interfaces annotated with `@RegisterAiService`. Quarkus LangChain4j generates the implementation at build time.

### Gameplay Architecture (Games + Play)

Solo play is modeled as a **Game** (identified by `gameId`) with a small state machine:

- `CHARACTER_CREATION` → `SCENE_INITIALIZATION` → `ACTIVE_PLAY`

Core components:

- **GameEngine**: Routes user input to the appropriate engine based on phase and commands (e.g. `/help`, `/status`, `/newcharacter`, `/roll`, `/start`)
- **ActorCreationEngine**: Runs a guided character creation flow backed by `ActorCreationAssistant` and a draft stored on the `GameState`
- **GamePlayEngine**: Runs turn processing backed by `GamePlayAssistant` and applies returned patches to update world state
- **PlayWebSocket**: Streams responses to the browser over WebSockets Next (`/ws/play/{gameId}`)

Game state is persisted in Neo4j via **neo4j-ogm-quarkus** using nodes like `Game`, `Actor`, `PlayerActor`, `Location`, and `Event`.

### Document Processing Pipeline

**IngestService** handles document ingestion:

1. **Upload**: Accepts markdown files via multipart form data (through web UI at `/ingest` or REST at `/api/lore/ingest`)
2. **Parse**: Handles two formats:
   - Structured markdown with YAML frontmatter and section headers
   - Plain text with recursive chunking
3. **Embed**: Generates embeddings using Ollama's nomic-embed-text
4. **Store**: Saves text segments + embeddings in Neo4j with metadata

### REST API

The HTTP API is organized into three main areas:

- **ChatResource** (`/api/chat`) - Generic LLM chat (setting-independent)
    - `GET/POST /api/chat` - Direct LLM chat (no retrieval)

- **LoreResource** (`/api/lore`) - RAG queries against ingested documents
    - `GET/POST /api/lore` - RAG queries with embedding retrieval
    - `GET /api/lore/doc?filename=...` - Retrieve a raw ingested document by filename
    - `GET /api/lore/files` - List ingested source files + counts
    - `GET /api/lore/adventures` - List discovered adventure names
    - `POST /api/lore/ingest` - Upload campaign documents (multipart form)
    - `DELETE /api/lore/all` - Delete all documents
    - `DELETE /api/lore/files?sourceFile=...` - Delete a specific source file

- **GameResource** (`/api/game`) - Game state inspection and management
    - `GET /api/game` - List games
    - `GET /api/game/{gameId}` - Retrieve a single game
    - `DELETE /api/game/{gameId}` - Delete a game (and all related nodes)
    - `GET /api/game/{gameId}/actors` - List actors
    - `GET /api/game/{gameId}/party` - List player-controlled actors
    - `GET /api/game/{gameId}/locations` - List locations
    - `GET /api/game/{gameId}/events` - List events

- `ChatResource` and `LoreResource` return HTML (markdown converted via CommonMark).
- `GameResource` returns JSON snapshots of stored game state.
- `PlayWebSocket` streams deltas and sends both markdown and rendered HTML on completion.

### WebSocket API (Play)

- **PlayWebSocket** (`/ws/play/{gameId}`) - Streaming play interactions (token-by-token deltas)
  - Client sends `history_request` and `user_message`
  - Server streams `assistant_start`, `assistant_delta`, and `assistant_done`

See `docs/ws-play.md` for a simple smoke test and message shapes.

### Data Flow

```
User Request → REST/WebSocket → Engine/Resource → AI Service Interface → LangChain4j
                                                                     ↓
                                                        [RAG: Query Neo4j Embeddings]
                                                                     ↓
                                                              Ollama LLM
                                                                     ↓
                                                    Response → Markdown → HTML
```

## Code Standards

- **Java 21**: Use modern Java features (records, pattern matching, etc.)
- **Quarkus REST**: Use `quarkus-rest` (not `quarkus-resteasy`)
    - Annotations: `@RestQuery`, `@RestForm` from `org.jboss.resteasy.reactive`
- **Dependency Injection**: Use `@Inject` and `@ApplicationScoped`
- **AI Services**: Declarative interfaces with `@RegisterAiService`

## Development Patterns

### Adding New AI Features

1. Create an interface annotated with `@RegisterAiService`
2. Define methods with `@UserMessage` or `@SystemMessage` annotations
3. Inject the interface where needed - implementation is automatic

Example:

```java
@RegisterAiService
@ApplicationScoped
public interface MyAssistant {
    @UserMessage("Analyze this: {text}")
    String analyze(String text);
}
```

### Working with Embeddings

When storing documents:

- Always include metadata (sourceFile, filename, canonical flag)
- Use `canonical: true` for source material vs generated content
- Configure chunk size/overlap via `campaign.chunk.size` and `campaign.chunk.overlap`

### Configuration

Key settings in [application.properties](src/main/resources/application.properties):

- `quarkus.langchain4j.ollama.chat-model.model-name` - LLM for chat (default: llama3.2)
- `quarkus.langchain4j.ollama.embedding-model.model-name` - Embedding model (default: nomic-embed-text)
- `campaign.chunk.size` / `campaign.chunk.overlap` - Document chunking parameters
- Neo4j connection and credentials

Quarkus configuration follows standard patterns:
- `application.properties` for default settings
- Environment variables or system properties for overrides
- `@ConfigProperty` for injection

## Project Structure

```
src/main/java/dev/ebullient/soloplay/
├── ai/                        # AI/LangChain4j components (assistants, retrieval, tools)
│   ├── ChatAssistant.java     # Basic chat assistant
│   ├── LoreAssistant.java     # RAG lore assistant
│   ├── LoreRetriever.java     # RetrievalAugmentor supplier
│   ├── LoreTools.java         # Tool: retrieve ingested docs by filename
│   └── memory/                # Chat memory persistence (Neo4j)
├── api/                       # REST API endpoints (Quarkus REST)
│   ├── ChatResource.java      # /api/chat
│   ├── LoreResource.java      # /api/lore
│   └── GameResource.java      # /api/game
├── play/                      # Solo play engine + websocket transport
│   ├── GameEngine.java        # Phase routing + command handling
│   ├── ActorCreationEngine.java
│   ├── GamePlayEngine.java
│   ├── GameTools.java         # AI tools for game state lookup
│   └── PlayWebSocket.java     # /ws/play/{gameId}
├── play/model/                # Neo4j OGM entities + patch/draft models
├── health/                    # Health checks (e.g., Neo4j availability)
├── ollama/                    # Optional REST client for Ollama API
├── web/                       # Web UI controllers (Renarde MVC)
├── IngestService.java         # Document ingestion & embeddings
├── GameRepository.java        # Game state persistence (Neo4j OGM)
├── LoreRepository.java        # Lore queries (documents/adventures)
└── StringUtils.java           # Shared string normalization/helpers
src/main/resources/
├── META-INF/resources         # static resources (Renarde front-end)
└── templates                  # Qute templates for Renarde views and tool responses
```

**Key Directories:**
- `ai/` - LangChain4j AI services and tools (assistants, tools, retrievers)
- `api/` - REST API endpoints using Quarkus REST
- `play/` - Game engines, tools, and WebSocket transport
- `play/model/` - Neo4j OGM entities and JSON patch/draft models
- `health/` - Health checks for dependencies (Neo4j, etc.)
- `ollama/` - Optional REST client for direct Ollama API calls
- `web/` - Web UI controllers using Renarde MVC

## API Reference

The application provides both REST APIs and web UI (Renarde MVC) for interacting with features.

### Chat

**Purpose:** Generic LLM chat without RAG or game context

- **REST API:** `GET/POST /api/chat`
  - Query param or body: `question` (string)
  - Returns: HTML (markdown converted)
- **Web UI:** `/chat`
  - Interactive chat page using REST API

### Lore (RAG Queries)

**Purpose:** Query campaign documents with semantic search and LLM augmentation

- **REST API:**
  - `GET/POST /api/lore` - Query lore with question
  - `GET /api/lore/doc?filename=...` - Retrieve raw markdown for a document
  - `GET /api/lore/files` - List ingested files and counts
  - `GET /api/lore/adventures` - List discovered adventure names
  - `POST /api/lore/ingest` - Upload campaign documents (multipart form)
  - `DELETE /api/lore/all` - Delete all documents
  - `DELETE /api/lore/files?sourceFile=...` - Delete specific file
- **Web UI:**
  - `/lore` - Lore query interface
  - `/ingest` - Document upload and management
- **Implementation:** Uses LoreAssistant (RAG), retrieves from Neo4j embeddings; LoreTools resolves document cross-references

### Game / Solo Play

**Purpose:** Game-aware solo play with character creation, turn processing, and persistent world state

- **WebSocket API:**
  - `ws://localhost:8080/ws/play/{gameId}` - Streaming play interactions
  - See `docs/ws-play.md` for message shapes and a smoke test
- **REST API:**
  - `GET /api/game` - List games
  - `GET /api/game/{gameId}` - Retrieve a game state snapshot
  - `DELETE /api/game/{gameId}` - Delete a game
  - `GET /api/game/{gameId}/actors` - List actors
  - `GET /api/game/{gameId}/party` - List player-controlled actors
  - `GET /api/game/{gameId}/locations` - List locations
  - `GET /api/game/{gameId}/events` - List events
- **Web UI:**
  - `/game` - List games and create new games
  - `/game/create` - Create a new game (optionally choose an ingested adventure)
  - `/play/{gameId}` - Main gameplay interface (WebSocket-based)
- **Implementation:**
  - Uses GameEngine for phase routing and persistence
  - Uses ActorCreationAssistant + ActorCreationEngine during character creation
  - Uses GamePlayAssistant + GamePlayEngine during active play
  - AI can invoke LoreTools and GameTools for context and continuity
  - GameRepository provides Neo4j persistence for game state entities

### Architecture Notes

**Web UI → Data Access Patterns:**
- **Chat/Lore web pages** → Call REST APIs via JavaScript
- **Game pages** → Use server-side Renarde controllers + REST for listing/inspection
- **Play page** → Uses WebSocket (`/ws/play/{gameId}`) for streaming interactions

**AI Tool Access:**
- LLM can autonomously invoke `@Tool` methods (LoreTools + GameTools)
- Tools are available to ActorCreationAssistant and GamePlayAssistant

## Data Flows

**Document Flow:**
```
Upload → Parse (YAML + chunks) → Embed (nomic-embed-text) → Store (Neo4j)
```

**Query Flow:**
```
User Question → Retrieve (Neo4j vector search) → Augment → LLM → Response
```

**Tool Flow:**
```
AI Response → Tool Call → Java Method → Neo4j → Result → Continue Generation
```

## AI-Assisted Contributions

We welcome contributions, including those created with AI assistance. Please ensure:

- **You understand the changes**: Be able to explain the rationale for your decisions
- **You've tested locally**: Run tests and verify functionality with local Ollama/Neo4j
- **Changes fit existing patterns**: Follow the architectural patterns described above
- **You've read the AI guidelines**: See [CLAUDE.md](CLAUDE.md) for AI assistant working guidelines

Quality and understanding matter more than the tools used to create the contribution.
