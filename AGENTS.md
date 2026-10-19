# Repository Guidelines

## Project Structure & Module Organization

Pikdo currently contains a Python Collector service. `collector/src/app.py` defines FastAPI routes, application lifecycle, and the development entry point. `collector/src/xhs/` contains the Playwright BrowserManager and collector, pure input/product parsers, Pydantic schemas, value sanitizers, and errors. Keep browser navigation and response capture in `browser_collector.py`, lifecycle management in `browser.py`, and parsing independently testable. There is no HTTPX upstream client, Resolve API, batch CLI, or archive system.

Tests live in `collector/tests/`. `collector/examples/` contains original upstream samples and supporting documentation. `docs/` holds reference specifications; implement only the requested scope, since those references also describe features outside Collector.

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

## Coding Style & Naming Conventions

Use four-space indentation, type annotations, snake_case functions and fields, and PascalCase classes. Follow surrounding code style. Ruff and mdformat are configured through the project tooling commands. Define public responses with Pydantic models and group parsing helpers by business domain. Preserve existing API fields and unified error responses when extending functionality.

## Testing Guidelines

Use pytest, `test_*.py` files, and `test_*` functions. API tests use FastAPI TestClient with explicitly injected fake collection services; browser tests use fake browser objects. HTTPX is only a development dependency for TestClient. Automated tests must not depend on live upstream requests. Opt-in Chromium tests use loopback pages only. No numeric coverage threshold is configured. Cover new behavior, malformed optional fields, error classification, precision, and backward compatibility.

## Data Safety & Business Rules

Treat `collector/examples/` as read-only original data, including byte layout; format/lint must skip it. Preserve historical local data and Git ignore rules. Never overwrite or format marked probe or historical data directories. Modify only in-memory fixture copies. Sanitize public output without mutating source data. Never expose complete upstream responses or credentials. Use Decimal for money, preserve unknown price units, keep missing sales null, and use Asia/Shanghai timestamps. Distinguish product IDs from SKU IDs and request failures from explicit delisting.

## Commit & Pull Request Guidelines

History uses descriptive, imperative subjects such as “Bump version…” and “Remove root redirect…”, without a mandatory prefix. Keep commits focused. PRs should explain behavior changes, link relevant issues when available, and report executed tests and build results. Document response changes with field mappings or JSON examples. Separate mock verification from real-network results.
