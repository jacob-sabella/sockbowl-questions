# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with
code in this repository.

## Project overview

`sockbowl-questions` is the content service for the Sockbowl quizbowl
platform: a Spring Boot 4.1.x / Spring GraphQL / Spring AI application backed
by Neo4j, sharing a Redis instance with `sockbowl-game` for rate limits,
quotas, usage tracking and bans. It owns packets, tossups and bonuses; the
game service reads them at match time through the game-only `EPHEMERAL`
visibility path and the `packet:read-answers` service token.

Java 25 via the Foojay toolchain resolver, Gradle 9.x wrapper. No Dockerfile:
the container image is built with Spring Boot's Cloud Native Buildpacks
support (`./gradlew bootBuildImage`).

## Package map (`com.soulsoftworks.sockbowlquestions`)

- **`models` / `models.nodes` / `models.relationships`** — Neo4j `@Node` /
  `@RelationshipProperties` entities (`Packet`, `Tossup`, `Bonus`,
  `BonusPart`, `Category`, `Difficulty`, `Subcategory`, `PacketVisibility`,
  provenance fields). This package is also compiled into the standalone
  **models jar** consumed by `sockbowl-game` and (via generated JSON Schema)
  by `sockbowl-ng`'s codegen — see "Models-jar contract" below.
- **`repository`** — Spring Data Neo4j repositories, including hand-written
  Cypher for visibility filtering, summaries and version queries.
- **`api`** / **`api.input`** — GraphQL controllers
  (`@Controller`/`@QueryMapping`/`@MutationMapping`) and REST controllers
  (packet import, bank stats/dimensions, taxonomy, admin usage). Thin: real
  orchestration lives in `service`.
- **`service`** / **`service.strategy`** — `PacketAuthoringService` (create,
  update, delete, clone, versioning via `expectedVersion`),
  `PacketImportService` / `QbreaderImportService` (plaintext and qbreader
  import), `QuestionGenerationService` + `strategy` (AI-generated tossups),
  `PacketValidator` (field-length and structural limits, playability
  checks), `TaxonomyService`.
- **`packetio`** — the plaintext packet parser/formatter used by manual
  import and export; pure, no Spring dependencies.
- **`security`** — `PacketReadPolicy` (who sees a packet, with or without
  answers), `PacketAuthorizationService` (per-packet ownership for
  `@PreAuthorize` SpEL), `AuthenticatedUser`, `SecurityAuditorAware`
  (owner/audit-field population).
- **`ratelimit`** / **`ratelimit.graphql`** — token-bucket rate limiting
  (`RateLimitService`, `PolicySpec`, `Tier`), the `UsageKeys` Redis key
  contract, the HTTP `RequestGuardFilter` and the GraphQL
  `RateLimitingInstrumentation` / field-level interceptor. This package is a
  close port of `sockbowl-game`'s own `ratelimit` package; keep the two in
  sync deliberately, not by accident.
- **`quota`** — daily/lifetime quota enforcement (`ContentQuotaGuard`,
  `QuotaService`) layered on top of the rate limiter.
- **`ban`** — IP and subject ban enforcement, mirrored from data
  `sockbowl-game` publishes into the shared Redis (D8).
- **`ai`** — `AiGenerationGuard` (charges the `ai-generate` policy and the
  per-subject quota exactly once per call, regardless of whether the caller
  used the REST or GraphQL path), `InflightLock` (per-subject concurrency
  lock so one caller can't run two generations at once), `AiPermit`.
- **`config`** — Spring configuration: `SecurityConfig` / `NoSecurityConfig`
  (mutually exclusive on `sockbowl.auth.enabled`), `AiConfig` /
  `AiChatModelSelectorEnvironmentPostProcessor` (provider selection),
  `GraphQlLimitsConfig` (depth/complexity caps), `Neo4jConfig` /
  `Neo4jAuditingConfig`, `PacketLimitsProperties`, `QuotaProperties`-adjacent
  properties classes, `TaxonomySchemaInitializer`.
- **`migration`** — one-shot startup backfills (`ProvenanceBackfillRunner`);
  new ones follow the same pattern: idempotent, logged, safe to run every
  boot.
- **`tooling`** — `ModelSchemaGenerator`, the `generateModelSchema` Gradle
  task's entry point (emits `questions-models.schema.json`, which
  `sockbowl-ng` codegens its TypeScript interfaces from).

## Security rules to keep

- **Deny by default when auth is on.** `SecurityConfig` and
  `NoSecurityConfig` are mutually exclusive `@Configuration` classes gated on
  `sockbowl.auth.enabled`; there's no code path that runs "half-secured".
  `NoSecurityConfig` (the default) is the self-hosted/local-dev posture, not
  a production one.
- **Never put answers where an unentitled caller can read them.** Every read
  path funnels through `PacketReadPolicy.canSee` / `canReadFull` and
  `PacketProjection.forCaller`. A caller who can see a packet but not read it
  in full gets the answer-free projection (`Tossup.answer` /
  `BonusPart.answer` nulled, `Packet.answersRedacted = true`), never a
  partially-redacted original. `PacketValidator`'s issue messages describe
  structure only (positions/counts) — never quote question or answer text —
  because a caller may see validation output for a packet whose answers are
  redacted for them.
- **Game-only (`EPHEMERAL`) packets are the narrowest case.** Only the game
  service's own token (`packet:read-answers`) reads them in full; even
  `packet:manage-any` cannot (its one exception is deletion). They never
  appear in `isListed`/search results for anyone. Read `PacketReadPolicy`'s
  class Javadoc before changing any of this — the rules are deliberately
  written against `PacketVisibility.isPubliclyReadable()` /
  `isGameOnly()` / `isListed()`, not against specific enum values, so a new
  visibility can be added without touching the policy methods.
- **Ownerless packets have no grandfather rule.** A packet with a null
  `ownerId` (legacy data, or imported while auth was off) is manageable only
  through `packet:manage-any` — see `PacketAuthorizationService`'s class
  Javadoc (closes PB-17). Don't add an "any author may edit if unowned" path.
- **`UsageKeys` must stay byte-for-byte identical to `sockbowl-game`'s copy.**
  Both services read and write the same Redis DB for rate limits, quotas,
  usage counters and bans. `UsageKeysContractTest` pins the constants here;
  `sockbowl-game` has the matching test. A key-schema change is one logical
  change across both repos, not two independent ones.
- **AI generation is charged exactly once per call.** `AiGenerationGuard` is
  the only place that charges the `ai-generate` rate policy and the
  `ai.generations` quota — never charge it again in a route/field mapping
  (see the comment on the `ai-generate` GraphQL field mapping in
  `application.yml`: `generateAndAddTossup` stays on `graphql-write` for the
  per-minute abuse cap; the AI cost itself is charged by the guard).
- **Most limiter/quota/ban checks fail open on a Redis outage (D12); AI
  generation fails closed.** Don't change either default without updating
  both the config comment and the corresponding `*FailOpenTest`/IT.
- **Models-jar version bump rule.** `build.gradle`'s `modelsLocalRevision`
  must be bumped whenever a class under `models.*` (or anything the
  generated JSON Schema captures) changes semantically. A `mavenLocal` build
  of `sockbowl-game` resolves `sockbowlquestions-models:1.0.+`; without a
  bump it can silently keep using a stale cached jar from `~/.m2`. Document
  the reason for each bump in the comment stack above
  `modelsLocalRevision`, the way earlier entries do.

## Tests

- `./gradlew test` — full suite (plain unit tests + Testcontainers `IT`
  suites against Neo4j, Redis and Keycloak; needs a working local Docker
  daemon, no other setup).
- No test depends on the host's Ollama instance; AI-generation tests inject
  `ai.ScriptedChatModel`, a scripted fake `ChatModel`.
- After touching anything under `ratelimit`, `quota`, `ban` or `security`,
  run at minimum: `UsageKeysContractTest`, the `ratelimit.*` and `quota.*`
  packages, `PacketReadPolicyTest`, `PacketAuthorizationTest`/
  `PacketAuthorizationServiceTest`.
- After touching `models.*`, run `PacketJsonCompatibilityTest` (guards the
  JSON shape `sockbowl-game` and `sockbowl-ng` depend on) and regenerate
  `questions-models.schema.json` (`./gradlew generateModelSchema`) if the
  shape changed on purpose.
- `SockbowlQuestionsApplicationTests` is the plain context-loads smoke test;
  it must pass with the default (no-Redis-required-at-boot) configuration.

## Working with `sockbowl-game`

- **Local build without a GitHub Packages token:**
  `./gradlew publishToMavenLocal` here, then build game with
  `-PsockbowlUseMavenLocal=true` (or `SOCKBOWL_USE_MAVEN_LOCAL=true`).
- **CI:** game's workflow checks out this repo at a pinned ref
  (`sockbowlQuestionsRef` in game's `gradle.properties`) and
  `publishToMavenLocal`s it for the test job, so CI doesn't need Packages
  read access; the publish job still resolves the real Packages artifact.
- Shared contracts to keep in lockstep across both repos: `UsageKeys` (Redis
  keys), the rate-limit tier names and multipliers, and the
  `models.*` JSON shape.

## Never do this

- Never return a packet's answers to a caller `PacketReadPolicy` says
  shouldn't have them, in any format (GraphQL, REST, validation messages,
  logs).
- Never let a `NoSecurityConfig`/`SecurityConfig` change leave a path
  reachable under both, or neither.
- Never change `UsageKeys` in this repo without the matching change in
  `sockbowl-game`, in the same change.
- Never bump `modelsLocalRevision` for a change that isn't in `models.*` (it
  exists to signal exactly one thing), and never skip bumping it for one
  that is.
