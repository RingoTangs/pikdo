# Repository Guidelines

## Project Structure & Module Organization

Pikdo currently contains a Python Collector service. `collector/src/app.py` defines FastAPI routes, application lifecycle, and the development entry point. `collector/src/xhs/` contains the HTTPX client, domain parsers, Pydantic schemas, value sanitizers, and business exceptions. Keep network access in `client.py` and parsing independently testable.

Tests live in `collector/tests/`. `collector/examples/` contains original upstream samples and supporting documentation. `docs/` holds reference specifications; implement only the requested scope, since those references also describe features outside Collector.

## Build, Test, and Development Commands

Run these commands from `collector/`:

- `uv sync`: install the locked project and development dependencies.
- `uv run dev`: start the API on `127.0.0.1:8000`; documentation is at `http://localhost:8000/docs`.
- `uv run test -v`: run all pytest tests verbosely.
- `uv run test tests/test_parser.py -q`: run parser tests only.
- `uv build`: produce source and wheel distributions in `dist/`.

Use Python 3.13 and uv; update `uv.lock` when dependencies or project metadata require it. Run `git diff --check` before submitting changes.

## Coding Style & Naming Conventions

Use four-space indentation, type annotations, snake_case functions and fields, and PascalCase classes. Follow surrounding code style. No formatter or linter is currently configured. Define public responses with Pydantic models and group parsing helpers by business domain. Preserve existing API fields and unified error responses when extending functionality.

## Testing Guidelines

Use pytest, `test_*.py` files, and `test_*` functions. API tests use FastAPI TestClient and HTTPX MockTransport; automated tests must not depend on live upstream requests. No numeric coverage threshold is configured. Cover new behavior, malformed optional fields, error classification, precision, and backward compatibility.

## Data Safety & Business Rules

Treat `collector/examples/` as read-only original data. Modify only in-memory fixture copies. Sanitize public output without mutating source data. Never expose complete upstream responses or credentials. Use Decimal for money, preserve unknown price units, keep missing sales null, and use Asia/Shanghai timestamps. Distinguish product IDs from SKU IDs and request failures from explicit delisting.

## Commit & Pull Request Guidelines

History uses descriptive, imperative subjects such as “Bump version…” and “Remove root redirect…”, without a mandatory prefix. Keep commits focused. PRs should explain behavior changes, link relevant issues when available, and report executed tests and build results. Document response changes with field mappings or JSON examples. Separate mock verification from real-network results.
