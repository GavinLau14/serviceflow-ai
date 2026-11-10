"""Isolated contract tests: fake retrieval/provider, never evidence of real model accuracy."""
import json
import pytest
import httpx
from fastapi.testclient import TestClient
from serviceflow_ai.app import create_app
from serviceflow_ai.core import Assistant, Citation, Settings, OpenAIProvider

MESSAGE = "空调无法制冷，请帮我报修"
CITATION = Citation(id="demo-ac", title="演示空调报修", source="demo://kb/ac", text="演示：空调无法制冷可提交家电报修。", score=.9)

class Retrieval:
    def __init__(self, chunks=None, fail=False):
        self.chunks = [CITATION] if chunks is None else chunks
        self.fail = fail
    def search(self, message):
        if self.fail: raise RuntimeError("secret backend details")
        return self.chunks
    def index(self): return {"indexed": 3, "collection": "test"}
    def health(self): return True

class Provider:
    def __init__(self, result): self.result=result
    def decide(self, message, chunks):
        if isinstance(self.result, Exception): raise self.result
        return self.result

def decision(**updates):
    result = {"intent":"REPAIR", "answer":CITATION.text, "confidence":.9, "handoff":False,
              "citation_ids":["demo-ac"], "proposal":{"title":MESSAGE[:100],"description":MESSAGE,"category":"APPLIANCE","priority":"NORMAL"}}
    result.update(updates)
    return result

def test_missing_token_fails_closed_and_health_exposes_no_config():
    client=TestClient(create_app(Settings(service_token=""), Retrieval()))
    assert client.post("/assist",json={"message":MESSAGE}).status_code==503
    assert set(client.get("/health").json())=={"mode","retrieval_ready","llm_configured"}

@pytest.mark.parametrize("path,body",[("/assist",{"message":MESSAGE}),("/index",{})])
def test_internal_routes_require_service_token(path,body):
    client=TestClient(create_app(Settings(service_token="test-token"), Retrieval()))
    assert client.post(path,json=body).status_code==401
    assert client.post(path,json=body,headers={"X-Service-Token":"incorrect"}).status_code==401

def test_retrieval_only_never_creates_proposal():
    result=Assistant(Retrieval(),None,.65).assist(MESSAGE)
    assert result.mode=="retrieval_only" and result.proposal is None and result.handoff
    assert CITATION.text in result.answer and result.citations==[CITATION]

def test_validated_rag_proposal_preserves_original_customer_message():
    result=Assistant(Retrieval(),Provider(decision()),.65).assist(MESSAGE)
    assert result.mode=="rag" and result.proposal.description==MESSAGE and not result.handoff
    assert result.citations==[CITATION]

@pytest.mark.parametrize("bad",[
    {"citation_ids":["invented"]}, {"citation_ids":[]}, {"confidence":.2}, {"confidence":float("nan")},
    {"proposal":{"title":"invented","description":"invented","category":"APPLIANCE","priority":"HIGH"}},
    {"proposal":{"title":MESSAGE[:100],"description":MESSAGE,"category":"SQL","priority":"HIGH"}},
    {"handoff":True}, {"extra":"unexpected"},
])
def test_invalid_or_low_evidence_provider_response_cannot_authorize_proposal(bad):
    result=Assistant(Retrieval(),Provider(decision(**bad)),.65).assist(MESSAGE)
    assert result.proposal is None and result.handoff

def test_empty_low_score_and_failure_degrade_honestly():
    for retriever in [Retrieval([]),Retrieval([CITATION.model_copy(update={"score":.1})]),Retrieval(fail=True)]:
        result=Assistant(retriever,Provider(decision()),.65).assist(MESSAGE)
        assert result.proposal is None and result.handoff
    result=Assistant(Retrieval(),Provider(RuntimeError("key=secret")),.65).assist(MESSAGE)
    assert result.mode=="error" and "secret" not in result.answer

def test_request_bounds_unknown_fields_and_index_no_arbitrary_input():
    client=TestClient(create_app(Settings(service_token="test-token"), Retrieval()))
    headers={"X-Service-Token":"test-token"}
    for body in [{"message":" "},{"message":"x"*5001},{"message":MESSAGE,"customerId":3}]:
        assert client.post("/assist",json=body,headers=headers).status_code==422
    assert client.post("/index",json={"url":"https://attacker.example"},headers=headers).status_code==422
    assert client.post("/index",json={},headers=headers).status_code==200

@pytest.mark.parametrize("tool_name",["create_ticket","delete_all"])
def test_fake_openai_http_tool_contract(tool_name):
    def handle(request):
        body=json.loads(request.content)
        assert body["tools"][0]["function"]["name"]=="create_ticket"
        payload=decision(); args=payload.pop("proposal")
        return httpx.Response(200,json={"choices":[{"message":{"content":json.dumps(payload),"tool_calls":[{"type":"function","function":{"name":tool_name,"arguments":json.dumps(args)}}]}}]})
    provider=OpenAIProvider(Settings(service_token="x",llm_api_key="test-only",llm_model="fake"),httpx.Client(transport=httpx.MockTransport(handle)))
    result=Assistant(Retrieval(),provider,.65).assist(MESSAGE)
    assert (result.proposal is not None)==(tool_name=="create_ticket")

@pytest.mark.parametrize("response",[
    {}, {"choices":[]}, {"choices":[{"message":{"content":"not json"}}]},
    {"choices":[{"message":{"content":json.dumps(decision()),"tool_calls":[]}}]},
])
def test_malformed_fake_http_model_responses_fail_closed(response):
    provider=OpenAIProvider(Settings(service_token="x",llm_api_key="test",llm_model="fake"),httpx.Client(transport=httpx.MockTransport(lambda request:httpx.Response(200,json=response))))
    result=Assistant(Retrieval(),provider,.65).assist(MESSAGE)
    assert result.mode=="error" and result.proposal is None and result.handoff

def test_retrieval_unavailable_is_http_success_with_honest_error_mode():
    client=TestClient(create_app(Settings(service_token="test-token"),Retrieval(fail=True)))
    response=client.post("/assist",json={"message":MESSAGE},headers={"X-Service-Token":"test-token"})
    assert response.status_code==200 and response.json()["mode"]=="error"
    assert "secret" not in response.text


def test_model_cannot_invent_policy_or_claim_created_ticket_even_with_valid_citation():
    result=Assistant(Retrieval(),Provider(decision(answer="工单已创建，承诺免费维修。")),.65).assist(MESSAGE)
    assert result.proposal is None and result.handoff
    assert "免费" not in result.answer

@pytest.mark.parametrize("known_citation",[True,False])
def test_standard_tool_only_model_reply_is_grounded_in_tool_metadata(known_citation):
    args=decision()["proposal"] | {"citation_ids":["demo-ac" if known_citation else "invented"],"confidence":.9}
    response={"choices":[{"message":{"content":None,"tool_calls":[{"type":"function","function":{"name":"create_ticket","arguments":json.dumps(args)}}]}}]}
    provider=OpenAIProvider(Settings(service_token="x",llm_api_key="test",llm_model="fake"),httpx.Client(transport=httpx.MockTransport(lambda request:httpx.Response(200,json=response))))
    result=Assistant(Retrieval(),provider,.65).assist(MESSAGE)
    assert (result.proposal is not None)==known_citation
    if known_citation:
        assert result.mode=="rag" and result.answer==CITATION.text

def test_customer_message_and_proposal_support_5000_characters():
    message="电脑无法开机"+"补充现象"*900
    assert len(message)<5000
    client=TestClient(create_app(Settings(service_token="test-token"),Retrieval()))
    response=client.post("/assist",json={"message":message},headers={"X-Service-Token":"test-token"})
    assert response.status_code==200

def test_non_ascii_service_token_is_rejected_without_server_error():
    client=TestClient(create_app(Settings(service_token="test-token"),Retrieval()))
    response=client.post("/assist",json={"message":MESSAGE},headers={b"X-Service-Token":b"invalid-\xe9"})
    assert response.status_code==401

def test_no_evidence_is_retrieval_only_even_when_model_is_configured():
    result=Assistant(Retrieval([]),Provider(RuntimeError("must not be called")),.45).assist(MESSAGE)
    assert result.mode=="retrieval_only" and result.handoff and result.proposal is None

def test_emoji_draft_title_respects_java_utf16_bound():
    message="😀"*100
    raw=decision(proposal={"title":message[:80],"description":message,"category":"DEVICE","priority":"NORMAL"})
    result=Assistant(Retrieval(),Provider(raw),.45).assist(message)
    assert result.proposal is not None
    assert len(result.proposal.title.encode("utf-16-le"))//2<=160
