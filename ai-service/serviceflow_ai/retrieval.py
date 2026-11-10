"""Real multilingual embeddings only. Fixed bundled demo KB; no external document URLs."""
import threading
import uuid
from qdrant_client import QdrantClient, models
from .core import Settings, Citation

MODEL="sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
DIMENSION=384
COLLECTION="serviceflow_paraphrase_multilingual_minilm_l12_384_v1"
DEMO_KB=(
    {"id":"demo-appliance-ac","title":"演示知识：空调报修","source":"demo://serviceflow/appliance-ac/v1","text":"【虚构演示知识，非真实服务政策】空调无法制冷、空调不制冷可提交家电 APPLIANCE 报修。描述应记录用户原话和故障现象。系统仅形成待确认草稿，客户确认后才创建工单；不承诺到达时间、价格或保修。"},
    {"id":"demo-device-computer","title":"演示知识：电脑设备报修","source":"demo://serviceflow/device-computer/v1","text":"【虚构演示知识，非真实服务政策】电脑无法开机、显示器故障可提交设备 DEVICE 报修。记录用户提供的故障描述，不索取密码。AI 只能形成待确认草稿，不能读取真实工单状态。"},
    {"id":"demo-human-handoff","title":"演示知识：人工接管与确认","source":"demo://serviceflow/human-handoff/v1","text":"【虚构演示知识，非真实服务政策】没有足够知识依据、涉及费用或服务政策确认时转人工。遇到漏电、冒烟、燃气等安全风险，应停止使用并联系现场专业人员处理，不由 AI 指导自行维修。任何工单草稿都必须由客户明确确认后才创建。"},
)

class QdrantRetrieval:
    def __init__(self,settings:Settings):
        self.settings=settings
        self.client=QdrantClient(url=settings.qdrant_url,timeout=8,check_compatibility=False)
        self._model=None
        self._model_lock=threading.Lock()

    def _embedding(self):
        with self._model_lock:
            if self._model is None:
                from fastembed import TextEmbedding
                self._model=TextEmbedding(model_name=MODEL,cache_dir=self.settings.embed_cache,threads=2)
            return self._model

    def health(self):
        return self.client.collection_exists(COLLECTION) and self.client.count(COLLECTION,exact=True).count>0

    def index(self):
        vectors=list(self._embedding().passage_embed([row["text"] for row in DEMO_KB]))
        if any(len(v)!=DIMENSION for v in vectors): raise ValueError("embedding dimension mismatch")
        if not self.client.collection_exists(COLLECTION):
            self.client.create_collection(COLLECTION,vectors_config=models.VectorParams(size=DIMENSION,distance=models.Distance.COSINE))
        config=self.client.get_collection(COLLECTION).config.params.vectors
        if not isinstance(config,models.VectorParams) or config.size!=DIMENSION or config.distance!=models.Distance.COSINE: raise ValueError("collection incompatible")
        points=[models.PointStruct(id=str(uuid.uuid5(uuid.NAMESPACE_URL,row["source"])),vector=v.tolist(),payload={**row,"embedding_model":MODEL,"kb_version":1}) for row,v in zip(DEMO_KB,vectors,strict=True)]
        self.client.upsert(COLLECTION,points=points,wait=True)
        return {"indexed":len(points),"collection":COLLECTION,"demo":True}

    def search(self,message:str):
        if not self.health(): return []
        vector=list(self._embedding().query_embed(message))[0]
        results=self.client.query_points(COLLECTION,query=vector.tolist(),limit=3,with_payload=True).points
        known={row["id"]:row for row in DEMO_KB}
        citations=[]
        for point in results:
            payload=point.payload or {}
            row=known.get(payload.get("id"))
            if row and all(payload.get(k)==v for k,v in row.items()) and payload.get("embedding_model")==MODEL and payload.get("kb_version")==1:
                citations.append(Citation(**row,score=max(-1.0,min(1.0,float(point.score)))))
        return citations
