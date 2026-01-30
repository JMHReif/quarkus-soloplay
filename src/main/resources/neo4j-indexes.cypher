// Neo4j schema setup (indexes/constraints)
// Run these commands in Neo4j Browser or via cypher-shell.

// ===== RAG Document Indexes =====

// Used by LoreRepository + IngestService (file listing, cross-reference lookup, and ordering).
CREATE INDEX document_source_file IF NOT EXISTS
FOR (d:Document) ON (d.sourceFile);

CREATE INDEX document_filename IF NOT EXISTS
FOR (d:Document) ON (d.filename);

CREATE INDEX document_adventure_name IF NOT EXISTS
FOR (d:Document) ON (d.adventureName);

// Speeds up document reconstruction by filename in section/chunk order.
CREATE INDEX document_filename_section_chunk IF NOT EXISTS
FOR (d:Document) ON (d.filename, d.sectionIndex, d.chunkIndex);

// ===== ChatMemory Indexes =====

// Unique constraint on chat memory ID
CREATE CONSTRAINT chat_memory_id_unique IF NOT EXISTS
FOR (m:ChatMemory) REQUIRE m.id IS UNIQUE;

// ===== Game State Indexes/Constraints =====

// Core IDs
CREATE CONSTRAINT game_game_id_unique IF NOT EXISTS
FOR (g:Game) REQUIRE g.gameId IS UNIQUE;

CREATE CONSTRAINT actor_id_unique IF NOT EXISTS
FOR (a:Actor) REQUIRE a.id IS UNIQUE;

CREATE CONSTRAINT player_actor_id_unique IF NOT EXISTS
FOR (a:PlayerActor) REQUIRE a.id IS UNIQUE;

CREATE CONSTRAINT location_id_unique IF NOT EXISTS
FOR (l:Location) REQUIRE l.id IS UNIQUE;

CREATE CONSTRAINT event_id_unique IF NOT EXISTS
FOR (e:Event) REQUIRE e.id IS UNIQUE;

// Common lookups/filters used by GameRepository
CREATE INDEX actor_game_id IF NOT EXISTS
FOR (a:Actor) ON (a.gameId);

CREATE INDEX player_actor_game_id IF NOT EXISTS
FOR (a:PlayerActor) ON (a.gameId);

CREATE INDEX actor_tags IF NOT EXISTS
FOR (a:Actor) ON (a.tags);

CREATE INDEX actor_aliases IF NOT EXISTS
FOR (a:Actor) ON (a.aliases);

CREATE INDEX actor_name_normalized IF NOT EXISTS
FOR (a:Actor) ON (a.normalizedName);

CREATE INDEX location_game_id IF NOT EXISTS
FOR (l:Location) ON (l.gameId);

CREATE INDEX location_tags IF NOT EXISTS
FOR (l:Location) ON (l.tags);

CREATE INDEX location_aliases IF NOT EXISTS
FOR (l:Location) ON (l.aliases);

CREATE INDEX location_name_normalized IF NOT EXISTS
FOR (l:Location) ON (l.normalizedName);

CREATE INDEX event_game_id IF NOT EXISTS
FOR (e:Event) ON (e.gameId);

CREATE INDEX event_game_id_turn_number IF NOT EXISTS
FOR (e:Event) ON (e.gameId, e.turnNumber);

CREATE INDEX event_tags IF NOT EXISTS
FOR (e:Event) ON (e.tags);

// To verify indexes were created
SHOW INDEXES;

// To check index usage in queries
// PROFILE MATCH (d:Document) WHERE d.filename = 'test.md' RETURN d.text ORDER BY d.sectionIndex, d.chunkIndex
