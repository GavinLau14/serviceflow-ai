"""Publication protocol and payload boundaries; real vectors are a separate opt-in test."""
import pytest
from fastapi.testclient import TestClient
from serviceflow_ai.app import create_app
from serviceflow_ai.core import Settings, PublicDocument
from serviceflow_ai.retrieval import verified_publication, MODEL

ID = "pub:2d21c5c2-19ac-41f7-a27b-66b03cfe4278"
DOC = {"id": ID, "version": 1, "title": "公开摘要", "source": "knowledge://serviceflow/publications/2d21c5c2-19ac-41f7-a27b-66b03cfe4278/v1", "text": "人工确认可公开的排查方法。"}

class Retrieval:
    def __init__(self): self.seen = None
    def health(self): return True
    def search(self, message, publications=()):
        self.seen = publications
        from serviceflow_ai.core import Citation
        return [Citation(**{k: v for k, v in p.model_dump().items() if k != "version"}, score=.9) for p in publications]
    def index(self): return {}
    def upsert_publication(self, doc): return {"id": doc.id, "version": doc.version, "indexed": 1}
    def delete_publication(self, ident): return {"id": ident, "deleted": True}

def test_internal_publication_endpoints_require_service_identity_and_strict_documents():
    client = TestClient(create_app(Settings(service_token="test-token"), Retrieval()))
    for path, body in [("/publications/upsert", {"document": DOC}), ("/publications/delete", {"id": ID})]:
        assert client.post(path, json=body).status_code == 401
        assert client.post(path, json=body, headers={"X-Service-Token": "test-token"}).status_code == 200
    bad = dict(DOC, source="https://attacker.invalid/private")
    assert client.post("/publications/upsert", json={"document": bad}, headers={"X-Service-Token": "test-token"}).status_code == 422
    assert client.post("/publications/upsert", json={"document": dict(DOC, ticketId=123)}, headers={"X-Service-Token": "test-token"}).status_code == 422

def test_assist_only_receives_the_current_request_publications_without_retaining_previous_requests():
    retrieval = Retrieval()
    client = TestClient(create_app(Settings(service_token="test-token"), retrieval))
    headers = {"X-Service-Token": "test-token"}
    result = client.post("/assist", json={"message": "排查方法", "publications": [DOC]}, headers=headers).json()
    assert result["mode"] == "retrieval_only" and result["citations"][0]["id"] == ID
    assert result["proposal"] is None
    result = client.post("/assist", json={"message": "排查方法"}, headers=headers).json()
    assert not result["citations"] and not retrieval.seen
    assert client.post("/assist", json={"message": "排查方法", "publications": [DOC] * 51}, headers=headers).status_code == 422

def test_qdrant_payload_needs_exact_current_document_and_embedding_identity():
    doc = PublicDocument.model_validate(DOC)
    payload = dict(DOC, embedding_model=MODEL)
    assert verified_publication(payload, [doc]) == doc
    assert verified_publication(payload, []) is None
    for field, value in [("text", "伪造政策"), ("title", "伪造标题"), ("version", 2), ("source", "demo://fake"), ("embedding_model", "different-model")]:
        assert verified_publication(dict(payload, **{field: value}), [doc]) is None
