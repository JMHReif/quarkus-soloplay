# Neo4j Setup and Optimization

## Database Indexes

Create indexes for optimal query performance after starting Neo4j.

## Apply Indexes

After starting Neo4j with `docker compose up -d`, apply the indexes:

```bash
# Apply indexes using docker compose
cat src/main/resources/neo4j-indexes.cypher | \
  docker compose exec -T neo4j cypher-shell -u neo4j -p devpassword
```

### Alternative: Neo4j Browser

1. Open Neo4j Browser at `http://localhost:7474`
2. Connect using credentials (`neo4j`/`devpassword`)
3. Copy and paste the contents of `src/main/resources/neo4j-indexes.cypher`
4. Execute the script

## Reset Database (Fresh Start)

To start completely fresh:

```bash
docker compose down -v  # WARNING: Deletes all data!
docker compose up -d    # Recreates empty database
# Then re-apply indexes (see above)
```

## Index Benefits

The indexes optimize these common operations:

- **File listing** - `MATCH (d:Document) WHERE d.sourceFile IS NOT NULL RETURN d.sourceFile, count(*)`
- **Cross-reference lookups** - `MATCH (d:Document) WHERE d.filename = $filename RETURN d.text ORDER BY d.sectionIndex, d.chunkIndex`
- **Adventure lists** - `MATCH (d:Document) WHERE d.adventureName IS NOT NULL RETURN DISTINCT d.adventureName`
- **Vector similarity search (RAG)** - `CALL db.index.vector.queryNodes('vector', 10, $embedding)`
- **Chunk linking** - `MATCH (d:Document) WHERE d.id = $id`
- **File node lookup/deletes** - `MATCH (f:File) WHERE f.filename = $filename` / `MATCH (f:File) WHERE f.sourceFile = $sourceFile`

## Verifying Indexes

To verify indexes were created successfully:

```cypher
SHOW INDEXES
```

To check if an index is being used in a query:

```cypher
PROFILE MATCH (d:Document) WHERE d.filename = 'test.md' RETURN d.text ORDER BY d.sectionIndex, d.chunkIndex
```

Look for `NodeIndexSeek` in the query plan.

## Performance Monitoring

Monitor query performance with:

```cypher
// Show slow queries
CALL dbms.listQueries() YIELD query, elapsedTimeMillis
WHERE elapsedTimeMillis > 1000
RETURN query, elapsedTimeMillis
ORDER BY elapsedTimeMillis DESC
```

## Constraints vs Indexes

- **Constraints** ensure data integrity (uniqueness) AND create indexes
- **Indexes** only improve query performance
- This script uses both appropriately:
    - `CONSTRAINT` for ID fields (ensures uniqueness)
    - `INDEX` for search and filter fields

## Re-indexing

If you need to rebuild indexes:

```cypher
// Drop indexes (constraints will remain)
DROP INDEX document_source_file IF EXISTS;
DROP INDEX vector IF EXISTS;
DROP INDEX document_filename IF EXISTS;
DROP INDEX document_adventure_name IF EXISTS;
DROP INDEX document_filename_section_chunk IF EXISTS;
DROP INDEX file_source_file IF EXISTS;

// Then re-run the index creation script
```
