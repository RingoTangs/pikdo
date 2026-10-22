# Repository Guidelines

## Project Structure & Module Organization

Pikdo is a Monorepo with two independently built services: `collector/` for Python data collection and `server/` for the Java SaaS backend foundation. The Server is a modular monolith; business modules and communication with Collector are not implemented yet.

`collector/src/app.py` defines FastAPI routes, application lifecycle, and the development entry point. `collector/src/xhs/` contains the Playwright BrowserManager and collector, pure input/product parsers, Pydantic schemas, value sanitizers, and errors. Keep shared Browser lifecycle management in `browser.py`, per-collection Context ownership in `browser_collector.py`, and page capture state in `browser_page.py`. Pure navigation and response helpers belong in `browser_navigation.py` and `browser_response.py`; configuration and shared contracts belong in `browser_config.py` and `browser_contracts.py`. Preserve legacy imports and runtime injection through `browser_collector.py`, and keep parsing independently testable. There is no HTTPX upstream client, Resolve API, batch CLI, or archive system.

`server/src/main/java/io/github/ringotangs/pikdo/server/` follows business modules with internal DDD layers. Keep `Application` in the root package, startup logging in `bootstrap/`, identity domain objects and Repository interfaces in `identity/domain/`, and RepositoryImpl classes, MyBatis Mappers, and Rows in `identity/persistence/`. Account binds a UserId to a typed USERNAME/EMAIL/PHONE LoginIdentifier; each user has at most one effective binding per type and shares one UserId-owned immutable PasswordCredential domain object. Its Repository implementation reuses the existing password Mapper; insertion accepts version zero only, and conditional updates return false for an absent record or version mismatch without retrying. Preserve EmailAddress rules and strict canonical E.164 phone syntax without country inference. V1 and V2 are immutable; V3 adds USERNAME with 3–32 ASCII letters/digits/underscores, a leading letter, ASCII case folding, and the exact reserved names admin/system/support. VerificationChallenge owns challenge binding, expiry, attempt limits, consumption, and invalidation rules; its Repository reuses EmailChallengeMapper row locks and conditional updates. Keep failed-attempt commits and registration atomicity intact. Exercise migrations only against isolated test databases during development work. Domain Repository interfaces depend only on domain types and the JDK; keep persistence implementations constructor-wired without changing Spring Bean registration. Shared ID generation lives in `shared/id/`, Request ID handling in `shared/web/request/`, and shared Web errors and fallback configuration in `shared/web/error/`. Preserve package-level `@NullMarked` declarations. Server tests mirror these packages; explicitly imported Controller fixtures live under `server/src/test/` and must not be auto-scanned or packaged in the production JAR.

Collector tests live in `collector/tests/`. `collector/examples/` contains original upstream samples and supporting documentation. `docs/` holds reference specifications; implement only the requested scope, since those references also describe features outside Collector.

## Build, Test, and Development Commands

Run these commands from `collector/`:

- `uv sync`: install the locked project and development dependencies.
- `uv run playwright install chromium`: install the browser binary matching Playwright.
- `uv run format` / `uv run lint`: format or check source files while skipping protected raw data.
- `uv run dev`: start the API on `127.0.0.1:8000`; documentation is at `http://localhost:8000/docs`.
- `uv run test -v`: run all pytest tests verbosely.
- `uv run test tests/test_parser.py -q`: run parser tests only.
- `uv build`: produce source and wheel distributions in `dist/`.

Use Python 3.13 and uv; update `uv.lock` when dependencies or project metadata require it. Run `git diff --check` before submitting changes.

Run Server commands from `server/` with JDK 21 and the Maven Wrapper (Maven 3.9.x):

- `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`: start development mode on port 8080.
- `./mvnw test`: run all Java tests.
- `./mvnw spotless:check` / `./mvnw spotless:apply`: check or format Java using Google Java Format AOSP.
- `./mvnw clean verify`: clean, test, package the executable JAR, and check formatting.

Preserve existing dependency overrides and Maven plugins unless the task requires a change. Default/prod disable Swagger; dev enables it. All profiles expose `/actuator/health` without component details; only `health` is exposed over Web.

## Coding Style & Naming Conventions

Use four-space indentation, type annotations, snake_case functions and fields, and PascalCase classes. Follow surrounding code style. Ruff and mdformat are configured through the project tooling commands. Define public responses with Pydantic models and group parsing helpers by business domain. Preserve existing API fields and unified error responses when extending functionality.

Java uses the `io.github.ringotangs.pikdo.server` namespace, PascalCase classes and camelCase members. Preserve package-level `@NullMarked` conventions and the existing `{code,message}` error contract. Keep Request ID generation, Servlet dispatch registration, MDC restoration, Locale resolution, translations, and production structured logging intact.

## Testing Guidelines

Use pytest, `test_*.py` files, and `test_*` functions. API tests use FastAPI TestClient with explicitly injected fake collection services; browser tests use fake browser objects. HTTPX is only a development dependency for TestClient. Automated tests must not depend on live upstream requests. Opt-in Chromium tests use loopback pages only. No numeric coverage threshold is configured. Cover new behavior, malformed optional fields, error classification, precision, and backward compatibility.

Server tests use JUnit and MockMvc. Preserve strict JSON/status assertions and English/Chinese error coverage. Test Controllers must be marked as test components and imported explicitly. Verify default/dev/prod isolation, including the absence of fixture routes and unexposed management endpoints. Do not introduce external services or SaaS infrastructure without task scope.

## Data Safety & Business Rules

Treat `collector/examples/` as read-only original data, including byte layout; format/lint must skip it. Preserve historical local data and Git ignore rules. Never overwrite or format marked probe or historical data directories. Modify only in-memory fixture copies. Sanitize public output without mutating source data. Never expose complete upstream responses or credentials. Use Decimal for money, preserve unknown price units, keep missing sales null, and use Asia/Shanghai timestamps. Distinguish product IDs from SKU IDs and request failures from explicit delisting.

## Commit & Pull Request Guidelines

History uses descriptive, imperative subjects such as “Bump version…” and “Remove root redirect…”, without a mandatory prefix. Keep commits focused. PRs should explain behavior changes, link relevant issues when available, and report executed tests and build results. Document response changes with field mappings or JSON examples. Separate mock verification from real-network results.

<!-- CODEGRAPH_START -->
## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.
<!-- CODEGRAPH_END -->
