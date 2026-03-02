# GitLab AI Code Review Sequence Diagrams

This directory contains sequence diagrams for all GitLab webhook functions and AI code review workflows in the Guard application.

## Available Diagrams

### 1. Push Event AI Code Review
**File:** `1-push-event-review.png`

**Description:** This diagram illustrates the workflow when a push event occurs on a feature branch in GitLab. The system validates the webhook, checks if the repository is ingested, performs vector similarity search for related code context, and generates an AI code review comment that is posted directly to the commit.

**Key Components:**
- GitLabWebhookController
- WebhookService
- PushEventHandler
- RepoIngestionWorkerService
- EmbeddingModel
- CodeChunkRepository
- ChatModel
- GitLab API

**Flow Highlights:**
- Webhook authentication via secret token
- Automatic repository ingestion if not found in vector DB
- Polling mechanism to wait for ingestion completion
- Vector similarity search for related code context
- AI-powered code review using ChatModel with context

---

### 2. Merge Request Event AI Code Review
**File:** `2-merge-request-review.png`

**Description:** This diagram shows the workflow when a merge request is opened or updated in GitLab. Similar to push events, but posts the AI review comment to the merge request notes instead of the commit.

**Key Components:**
- GitLabWebhookController
- WebhookService
- MergeRequestEventHandler
- RepoIngestionWorkerService
- EmbeddingModel
- CodeChunkRepository
- ChatModel
- GitLab API

**Flow Highlights:**
- Triggers on "open" or "update" actions
- Retrieves merge request changes via GitLab API
- Ensures repository is ingested before review
- Performs vector similarity search for contextual code
- Posts AI review comment to merge request notes

---

### 3. Merge Event Delta Sync
**File:** `3-merge-event-delta-sync.png`

**Description:** This diagram illustrates the delta synchronization workflow that occurs when a merge request is successfully merged into the default branch. It updates the vector database with only the changed files to maintain an accurate codebase representation.

**Key Components:**
- GitLabWebhookController
- WebhookService
- MergeRequestEventHandler
- RepoIngestionWorkerService
- Git (JGit)
- VectorEmbeddingService
- CodeChunkRepository

**Flow Highlights:**
- Triggers only on "merge" action
- Validates merge is into default branch (protects vector DB integrity)
- Categorizes changes into filesToUpdate and filesToDelete
- Performs shallow clone (depth=1) for efficiency
- Deletes old vectors and inserts new ones for changed files only
- Asynchronous processing to avoid blocking

---

### 4. Repository Ingestion
**File:** `4-repository-ingestion.png`

**Description:** This diagram shows the complete repository ingestion workflow, from initial validation through cloning, file processing, embedding generation, and database storage.

**Key Components:**
- RepoController
- RepoIngestionWorkerService
- GitValidationService
- JGit
- VectorEmbeddingService
- EmbeddingModel
- CodeChunkRepository

**Flow Highlights:**
- Repository validation before cloning
- Full repository clone using JGit with authentication
- File filtering (.java, .kt, .md, .gradle.kts)
- Text chunking for optimal embedding size
- Batch embedding generation with retry logic
- Transactional database operations
- Cleanup of temporary directories

---

### 5. Repository Validation
**File:** `5-repository-validation.png`

**Description:** This diagram details the repository validation process using JGit's ls-remote command to verify repository accessibility without performing a full clone.

**Key Components:**
- Caller (RepoIngestionWorkerService or external client)
- GitValidationService
- JGit LsRemoteCommand
- GitLab Remote Repository

**Flow Highlights:**
- Uses ls-remote to check repository existence
- Supports both public and private repositories
- Handles authentication via UsernamePasswordCredentialsProvider
- Returns boolean validation result
- Minimal network overhead (no full clone)

---

## Architecture Overview

The Guard application implements a sophisticated AI-powered code review system for GitLab repositories. The architecture follows these key principles:

### Strategy Pattern
- `GitlabEventHandler` interface with implementations for different event types
- `WebhookService` routes events to appropriate handlers
- Extensible design for adding new event types

### Template Method Pattern
- `AbstractGitLabEventHandler` defines the AI review pipeline skeleton
- Subclasses implement specific comment posting logic
- Promotes code reuse and consistency

### Asynchronous Processing
- All webhook processing runs asynchronously to avoid GitLab timeouts
- Uses `@Async` annotation for non-blocking operations
- Background threads handle long-running tasks (ingestion, AI review)

### Vector Similarity Search
- Code chunks stored with embeddings in PostgreSQL with pgvector
- Semantic search for related code context
- Enhances AI review quality with relevant codebase knowledge

### Retry and Resilience
- Exponential backoff for embedding generation failures
- Polling mechanism for repository ingestion completion
- Graceful error handling and logging

---

## Configuration

The following properties control the behavior of these workflows:

```properties
# GitLab Configuration
gitlab.api.url=<GitLab instance URL>
gitlab.api.token=<GitLab access token>
gitlab.webhook.secret=<Webhook secret for authentication>
gitlab.path=<Webhook endpoint path>

# Feature Flags
guard.feature.push-review.enabled=<true/false>
```

---

## Supported File Types

The ingestion process currently supports:
- `.java` - Java source files
- `.kt` - Kotlin source files
- `.md` - Markdown documentation
- `.gradle.kts` - Gradle Kotlin DSL build scripts

Binary files and common image formats are automatically excluded.

---

## Database Schema

The system uses a `code_chunk` table with the following key fields:
- `id` - Primary key
- `repo_url` - Repository URL for filtering
- `file_path` - File path within repository
- `content` - Text content of the chunk
- `embedding` - Vector embedding (pgvector type)

---

## Error Handling

Each workflow includes comprehensive error handling:
- **Authentication Failures:** Webhook requests with invalid tokens are rejected
- **Validation Failures:** Inaccessible repositories are logged and skipped
- **Ingestion Failures:** Exceptions are logged, temp directories are cleaned up
- **Embedding Failures:** Retry logic with exponential backoff
- **API Failures:** GitLab API errors are caught and logged

---

## Performance Optimizations

1. **Delta Sync:** Only updates changed files instead of re-ingesting entire repository
2. **Shallow Clone:** Uses depth=1 for delta sync to minimize data transfer
3. **Batch Operations:** Bulk inserts and deletes for database efficiency
4. **Text Chunking:** Smart splitting to stay within token limits
5. **Deduplication:** Prevents redundant context in AI prompts
6. **Parallel Processing:** Asynchronous operations don't block webhook responses

---

## Security Considerations

- Webhook secret token validation prevents unauthorized requests
- Repository credentials use OAuth2 tokens
- Sensitive data not logged
- Temporary directories cleaned up after processing
- Branch protection: Only default branch merges trigger vector DB updates

---

## Future Enhancements

Potential areas for expansion:
- Additional event types (issue comments, code review comments)
- Support for more file types
- Configurable chunking strategies
- Multi-repository search
- Advanced AI models for specialized reviews (security, performance)
- Real-time streaming for large diffs

---

## Related Documentation

- Spring Boot Framework: https://spring.io/projects/spring-boot
- Spring AI: https://spring.io/projects/spring-ai
- GitLab Webhooks: https://docs.gitlab.com/ee/user/project/integrations/webhooks.html
- pgvector: https://github.com/pgvector/pgvector
- JGit: https://www.eclipse.org/jgit/
