数据采集服务

在 `collector` 目录启动：

```bash
uv run dev
```

入口为 `src/app.py` 中的 `main()`，通过 `pyproject.toml` 的 `[project.scripts]` 配置。

源码直接放在 `src` 下。以后可以添加 `src/utils/__init__.py` 和
`src/utils/text.py`，并使用 `from utils.text import ...` 导入。
包含 `__init__.py` 的包会自动发现；新增独立的顶层 `.py` 模块时，
需要将模块名称加入 `[tool.setuptools]` 的 `py-modules`。
