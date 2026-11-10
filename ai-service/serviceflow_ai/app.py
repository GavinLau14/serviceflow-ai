"""Authenticated internal endpoints; no customer identity or database credentials."""
import secrets
import threading
from fastapi import FastAPI, Header, Depends, HTTPException
from pydantic import Field, field_validator
from .core import Settings, StrictModel, AssistResponse, Assistant, OpenAIProvider

class AssistRequest(StrictModel):
    message: str = Field(min_length=1,max_length=5000)
    @field_validator("message")
    @classmethod
    def nonblank(cls,value):
        if not value.strip(): raise ValueError("message must not be blank")
        return value

class IndexRequest(StrictModel):
    pass

def create_app(settings:Settings|None=None,retrieval=None)->FastAPI:
    settings=settings or Settings()
    if retrieval is None:
        from .retrieval import QdrantRetrieval
        retrieval=QdrantRetrieval(settings)
    provider=OpenAIProvider(settings) if settings.llm_api_key and settings.llm_model else None
    assistant=Assistant(retrieval,provider,settings.min_score)
    app=FastAPI(title="ServiceFlow Internal AI",docs_url=None,redoc_url=None,openapi_url=None)
    index_lock=threading.Lock()

    def authenticated(x_service_token:str|None=Header(default=None)):
        if not settings.service_token: raise HTTPException(503,"AI service token is not configured")
        if not x_service_token or not secrets.compare_digest(x_service_token.encode("utf-8"),settings.service_token.encode("utf-8")): raise HTTPException(401,"Invalid service token")

    @app.get("/health")
    def health():
        try: ready=retrieval.health()
        except Exception: ready=False
        return {"mode":("rag" if provider else "retrieval_only") if ready and settings.service_token else "disabled","retrieval_ready":ready,"llm_configured":provider is not None}

    @app.post("/assist",response_model=AssistResponse,dependencies=[Depends(authenticated)])
    def assist(request:AssistRequest):
        return assistant.assist(request.message)

    @app.post("/index",dependencies=[Depends(authenticated)])
    def index(request:IndexRequest):
        if not index_lock.acquire(blocking=False): raise HTTPException(409,"Index operation already running")
        try: return retrieval.index()
        except Exception: raise HTTPException(503,"Embedding or vector service unavailable; index not confirmed") from None
        finally: index_lock.release()
    return app

app=create_app()
