"""Opt-in smoke using real local FastEmbed ONNX and external Qdrant. No fake vectors."""
import os
import pytest

@pytest.mark.real
@pytest.mark.skipif(os.environ.get("RUN_REAL_AI_SMOKE")!="1",reason="set RUN_REAL_AI_SMOKE=1 with real Qdrant running")
def test_real_multilingual_embedding_index_and_retrieval():
    from serviceflow_ai.core import Settings, Assistant
    from serviceflow_ai.retrieval import QdrantRetrieval
    retrieval=QdrantRetrieval(Settings())
    result=retrieval.index()
    assert result["indexed"]==3
    assert "384" in result["collection"]
    chunks=retrieval.search("空调不制冷需要维修")
    assert chunks and chunks[0].id=="demo-appliance-ac"
    response=Assistant(retrieval,None,Settings().min_score).assist("空调不制冷需要维修")
    assert response.mode=="retrieval_only" and response.proposal is None
    assert response.citations and "演示" in response.answer

    unrelated=Assistant(retrieval,None,Settings().min_score).assist("黑洞霍金辐射公式")
    assert unrelated.mode=="retrieval_only" and unrelated.handoff
    assert not unrelated.citations and unrelated.proposal is None
