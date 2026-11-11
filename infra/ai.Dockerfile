FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 AI_EMBED_CACHE=/var/cache/serviceflow
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/* && useradd -u 10001 -m serviceflow && mkdir -p /var/cache/serviceflow && chown serviceflow /var/cache/serviceflow
WORKDIR /app
COPY ai-service/requirements.lock ./requirements.lock
RUN --mount=type=cache,target=/root/.cache/pip pip install -r requirements.lock
COPY ai-service/serviceflow_ai ./serviceflow_ai
USER serviceflow
EXPOSE 18082
CMD ["python","-m","uvicorn","serviceflow_ai.app:app","--host","0.0.0.0","--port","18082"]
