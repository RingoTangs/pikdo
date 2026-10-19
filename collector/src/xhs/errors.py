class CollectionError(Exception):
    def __init__(
        self, code: str, message: str, status_code: int = 502, *, upstream_status: int | None = None
    ):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.headers = (
            {"X-Collector-Upstream-Status": str(upstream_status), "X-Collector-Blocked": "true"}
            if upstream_status is not None
            else {}
        )


class ResolveError(Exception):
    """A safe, public resolution failure, independent of collection errors."""

    def __init__(
        self, code: str, message: str, status_code: int = 422, *, upstream_status: int | None = None
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.headers = (
            {"X-Collector-Upstream-Status": str(upstream_status), "X-Collector-Blocked": "true"}
            if upstream_status is not None
            else {}
        )
