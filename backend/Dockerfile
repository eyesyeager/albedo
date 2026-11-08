# 构建上下文：仓库根目录
#   docker build -f backend/Dockerfile -t albedo-backend:local .
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    UV_PROJECT_ENVIRONMENT=/app/backend/.venv

WORKDIR /app/backend

# 固定 uv 版本，按 uv.lock 精确还原依赖（含 cos-python-sdk-v5）。
RUN pip install --no-cache-dir uv==0.12.15

COPY backend/pyproject.toml backend/uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY backend/ ./

ENV PATH="/app/backend/.venv/bin:$PATH"

# var/ 下的导出文件、上传文件、Skill 本地包与缓存需要可写；
# named volume 首次挂载时会继承镜像内该目录的属主。
RUN groupadd -g 10001 appuser \
    && useradd -u 10001 -g appuser -m appuser \
    && mkdir -p var/exports var/uploads var/skills var/skill-cache var/backups var/import-cache \
    && chown -R appuser:appuser /app/backend/var

USER appuser
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD python -c "import sys,urllib.request; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3).status == 200 else 1)"

# 生产去掉 --reload；由外部前端网关反代，端口映射默认只绑回环。
# 仅保留 --proxy-headers（默认信任 127.0.0.1 的 X-Forwarded-*），
# 不用 --forwarded-allow-ips "*"，避免后端端口被间接暴露时伪造来源头。
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]
