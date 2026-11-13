"""Opt-in real embedding/allow-list/cleanup smoke in an isolated collection."""
import os
import uuid
import pytest

@pytest.mark.real
@pytest.mark.skipif(os.environ.get("RUN_REAL_AI_SMOKE") != "1", reason="explicit real vector test opt-in required")
def test_real_publication_retrieval_allowlist_tamper_and_delete(monkeypatch):
    import serviceflow_ai.retrieval as module
    from serviceflow_ai.core import Settings, PublicDocument, Assistant
    collection = module.PUBLIC_COLLECTION + "_test_" + uuid.uuid4().hex[:10]
    monkeypatch.setattr(module, "PUBLIC_COLLECTION", collection)
    retrieval = module.QdrantRetrieval(Settings())
    ident = "pub:" + str(uuid.uuid4())
    doc = PublicDocument(id=ident, version=1, title="光栅零点漂移记录", source=f"knowledge://serviceflow/publications/{ident[4:]}/v1", text="【虚构演示】光栅零点漂移需要记录校准前后的观测值，停止使用异常测量结果，交由专业人员核验。")
    try:
        ack = retrieval.upsert_publication(doc)
        assert ack["indexed"] == 1
        hits = retrieval.search("光栅零点漂移需要记录校准前后的观测值", [doc])
        assert any(c.id == ident and c.text == doc.text for c in hits)
        assert all(c.id != ident for c in retrieval.search("光栅零点漂移", []))
        response = Assistant(retrieval, None).assist("光栅零点漂移需要记录校准前后的观测值", [doc])
        assert response.mode == "retrieval_only" and response.proposal is None
        point = str(uuid.uuid5(uuid.NAMESPACE_URL, "publication:" + ident))
        retrieval.client.set_payload(collection, payload={"text": "不可信的篡改内容"}, points=[point], wait=True)
        assert all(c.id != ident for c in retrieval.search("光栅零点漂移", [doc]))
        retrieval.upsert_publication(doc)
        assert retrieval.delete_publication(ident)["deleted"]
        assert all(c.id != ident for c in retrieval.search("光栅零点漂移", [doc]))
    finally:
        if retrieval.client.collection_exists(collection): retrieval.client.delete_collection(collection)
