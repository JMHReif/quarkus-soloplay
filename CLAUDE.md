# AI Assistant Guidelines

**Read [README.md](README.md) first for what this app demonstrates and how its frontend works.
For architecture, build commands, and API reference, see [CONTRIBUTING.md](CONTRIBUTING.md).**

## Your Role

Act as a pair programming partner:

- **REVIEW FIRST**: Read existing code before making changes. Understand existing patterns.
- **BE EFFICIENT**: Be succinct and concise
- **RESPECT PRIVACY**: Do not read .env* files unless instructed
- **NO SPECULATION**: Never make up code or guess at API behavior
- **ASK**: If implementation choices are unclear, ask for clarification

## Quick Commands

```bash
./mvnw quarkus:dev              # Dev mode with live reload (ask first)
./mvnw test                     # Run tests
./mvnw test -Dtest=ClassName    # Single test
```

## Key Patterns

### Neo4j OGM Sessions

Sessions are opened via `sessionFactory.openSession()` WITHOUT explicit closing. Connection pooling is managed by the extension.

```java
public List<Movie> findAll() {
    Session session = sessionFactory.openSession();
    return session.loadAll(Movie.class);
    // No session.close() needed
}
```

- Read-only: Open session, execute, return (no transaction)
- Writes: Use `session.beginTransaction()`, commit/rollback, close transaction

### Renarde MVC

- [Controllers](https://docs.quarkiverse.io/quarkus-renarde/dev/concepts.html#controllers)
- [Flash scope](https://docs.quarkiverse.io/quarkus-renarde/dev/advanced.html#flash_scope)
- [Redirects/Routing](https://docs.quarkiverse.io/quarkus-renarde/dev/advanced.html#routing)

## When Making Changes

1. **Read similar code first** - Find existing patterns and follow them
2. **Understand the flow** - User request → REST → AI Service → LangChain4j → Ollama → Response
3. **Check configuration** - Many behaviors are configurable via application.properties
4. **Test with real services** - Requires Ollama and Neo4j running locally
