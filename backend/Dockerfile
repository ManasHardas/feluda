# syntax=docker/dockerfile:1
# Pinned base image tag; dependencies come from uv.lock, so builds are repeatable.
FROM python:3.12.8-slim-bookworm AS build
COPY --from=ghcr.io/astral-sh/uv:0.5.14 /uv /usr/local/bin/uv
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PROJECT_ENVIRONMENT=/app/.venv
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

# Test image: `docker compose run --rm test` runs the suite next to the database container.
FROM build AS test
RUN uv sync --frozen
COPY watchdog ./watchdog
COPY migrations ./migrations
COPY tests ./tests
ENV PATH="/app/.venv/bin:$PATH" PYTHONDONTWRITEBYTECODE=1
CMD ["pytest", "-p", "no:cacheprovider"]

FROM python:3.12.8-slim-bookworm AS runtime
RUN useradd --system --uid 10001 --home /app watchdog
WORKDIR /app
COPY --from=build /app/.venv /app/.venv
COPY watchdog ./watchdog
COPY migrations ./migrations
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
USER 10001
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=4s --start-period=10s --retries=3 \
  CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3).status == 200 else 1)"]
# One worker process: the in-process rate limiter and background worker assume a single process per instance.
CMD ["uvicorn", "watchdog.asgi:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*", "--no-server-header"]
