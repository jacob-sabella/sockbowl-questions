# Sockbowl Questions

Sockbowl Questions is the backend service that owns quizbowl content for the
Sockbowl platform: packets, tossups and bonuses stored in Neo4j, a GraphQL API
for search and authoring, AI-assisted question generation, and the
rate-limit/quota/ban enforcement shared with `sockbowl-game`.

## Stack

- **Java 25** (provisioned automatically via the Foojay toolchain resolver —
  you do not need a JDK installed to build)
- **Spring Boot 4.1.x**, **Spring GraphQL**, **Spring AI 2.0.x**
- **Gradle 9.x** (via the wrapper, `./gradlew`)
- **Neo4j** for packet/question storage and (optionally) vector search
- **Redis** shared with `sockbowl-game` for rate limits, quotas, usage and bans
- **Keycloak** (OAuth2 resource server) when auth is enabled

## Building and testing

```bash
./gradlew build          # compile, unit + integration tests, assemble
./gradlew test           # tests only
./gradlew bootRun         # run locally (needs Neo4j and, for most features, Redis)
```

Integration tests use Testcontainers (Neo4j, Keycloak, Redis) and need a
working local Docker daemon; no other setup is required for `./gradlew test`.

### Building the models jar for `sockbowl-game`

`sockbowl-game` depends on this repo's data models
(`com.soulsoftworks:sockbowlquestions-models`) so both services agree on the
packet/tossup/bonus shape. In CI this resolves from GitHub Packages. For a
local build without a Packages token, publish it to your local Maven
repository first:

```bash
./gradlew publishToMavenLocal
```

then build `sockbowl-game` with `-PsockbowlUseMavenLocal=true` (or the
`SOCKBOWL_USE_MAVEN_LOCAL=true` environment variable). See
`sockbowl-game`'s own README for the build-side half of this. The published
version is `1.0.<modelsLocalRevision>` (see the comment on
`modelsLocalRevision` in `build.gradle`); bump that number whenever a model
class changes, so a `mavenLocal` build of game picks up the new jar instead of
silently reusing a stale one from `~/.m2`.

### Building the container image

The image is built with Spring Boot's Cloud Native Buildpacks support, not a
Dockerfile:

```bash
./gradlew bootBuildImage
```

This produces `ghcr.io/<owner>/sockbowl-questions:<version>` by default. To
use it with `sockbowl-docker`'s `docker-compose.build.yml`, re-tag it as
`sockbowl-questions:local`.

## Configuration

All runtime configuration lives in `src/main/resources/application.yml`, with
environment-variable overrides. The full list of variables this service
consumes, their defaults and what they do is documented in
`sockbowl-docker`'s `.env.example` and `CLAUDE.md` (the single place all four
repos' variables are kept in sync); the sections below cover the ones most
relevant to working in this repo directly.

### Auth

- `SOCKBOWL_AUTH_ENABLED` (default `false`): when `true`, requests are
  authenticated against Keycloak (`KEYCLOAK_ISSUER_URI`,
  `KEYCLOAK_JWK_SET_URI`) and every mutation/read is subject to the
  authorization rules in `security.PacketReadPolicy` and
  `security.PacketAuthorizationService`. When `false`, `NoSecurityConfig`
  permits every URL and all packets are fully readable — this is the
  self-hosted / local-dev default, not a production posture.
- `SOCKBOWL_AUTH_AUDIENCE` (default `sockbowl-api`): required `aud` claim on
  incoming bearer tokens.

### Rate limits and quotas

`sockbowl.ratelimit.*` configures per-route and per-GraphQL-field token
buckets backed by Redis (the same Redis instance and key schema as
`sockbowl-game` — see `ratelimit.UsageKeys`). `sockbowl.quota.*` configures
daily/lifetime caps per tier (AI generations, imports, packets owned). Both
fail differently on a Redis outage: most checks fail *open* (D12), while AI
generation fails *closed* (a 503 rather than an unmetered generation). See the
comments in `application.yml` next to each `SOCKBOWL_RL_*` / `SOCKBOWL_QUOTA_*`
placeholder for exact policy names and defaults.

### AI question generation

- `SOCKBOWL_AI_PROVIDER` (default `openai`): `openai` or `ollama`.
- `SOCKBOWL_REQUIRE_USER_API_KEY` (default `true`): when true, callers must
  supply their own key via `X-API-Key`; when false, the server's own
  `OPENAI_API_KEY` is used and is subject to
  `SOCKBOWL_AI_SERVER_DAILY_BUDGET` and `SOCKBOWL_AI_SERVER_ALLOWED_MODELS`.
- Ollama embeddings (`spring.ai.ollama.embedding.*`) require a local Ollama
  instance with the `mxbai-embed-large` model pulled
  (`ollama pull mxbai-embed-large`); this is a host prerequisite, not
  something the app can install for you.

### Health

`/actuator/health` is the only exposed actuator endpoint, used by the compose
healthcheck. The Redis health indicator is disabled deliberately
(`management.health.redis.enabled: false`): every limiter/quota/ban check
already fails open on a Redis outage, so a Redis blip should not take this
service's health check down and cascade into game/ng being torn down by
`depends_on: service_healthy`.

## Security notes

- **Deny by default when auth is on.** `NoSecurityConfig` (auth disabled) and
  `SecurityConfig` (auth enabled) are mutually exclusive `@Configuration`
  classes; there is no partial state.
- **Answers never leave the service to a caller who isn't entitled to them.**
  `security.PacketReadPolicy` decides who may see a packet at all
  (`canSee`) versus who may see it with answers (`canReadFull`); everyone else
  gets `api.PacketProjection`'s answer-free deep copy. Game-only
  (`EPHEMERAL`) packets are only ever read in full by the game service's own
  token (`packet:read-answers`), never by `packet:manage-any` and never
  listed in search results for anyone.
- **Ownerless packets have no grandfather rule.** A packet with no
  `ownerId` (legacy data, or imported with auth off) can only be managed via
  `packet:manage-any` — see `security.PacketAuthorizationService`.
- **The Redis key schema (`ratelimit.UsageKeys`) must stay byte-for-byte
  identical to `sockbowl-game`'s copy.** `UsageKeysContractTest` pins the
  constants in this repo; `sockbowl-game` has the matching test. Change one
  side, change the other, in the same logical change.
- **The models-jar version (`modelsLocalRevision` in `build.gradle`) must be
  bumped whenever a class under `models.*` changes**, so a `mavenLocal` build
  of `sockbowl-game` doesn't silently keep using a stale cached jar.

## Tests

- `./gradlew test` runs the full suite: plain unit tests plus Testcontainers
  integration tests (suffixed `IT`) against real Neo4j, Redis and Keycloak
  containers.
- No test currently needs the host's Ollama instance; AI-generation tests use
  a scripted fake `ChatModel` (`ai.ScriptedChatModel`).
- `UsageKeysContractTest` and the `ratelimit`/`quota`/`ban` packages'
  suites are the ones to run first after touching anything shared with
  `sockbowl-game`.

## Related docs

- `CLAUDE.md` in this repo — architecture and conventions for anyone (human
  or agent) changing this code.
- `sockbowl-docker`'s `README.md`, `CLAUDE.md`, `docs/limits.md` and
  `docs/auth.md` — the cross-service picture, environment variables, and how
  this service fits the compose stack.
