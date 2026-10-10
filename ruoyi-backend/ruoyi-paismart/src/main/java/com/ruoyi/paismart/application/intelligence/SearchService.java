package com.ruoyi.paismart.application.intelligence;
import com.ruoyi.paismart.application.documents.DocumentService;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.infrastructure.intelligence.*;
import com.ruoyi.paismart.infrastructure.Jsons;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Executor;

@Service
@ConditionalOnProperty(name="paismart.enabled",havingValue="true")
public class SearchService {
    @org.springframework.beans.factory.annotation.Autowired private com.ruoyi.paismart.application.MembershipService members;
    public record Evidence(String id,String documentId,int contentVersion,int chunkNo,Integer page,String title,String text,String anchor,String context,double score,String scoreType,String parentContext,String parentKey) {
        public Evidence(String id,String documentId,int version,int no,Integer page,String title,String text,String anchor,String context,double score,String type){this(id,documentId,version,no,page,title,text,anchor,context,score,type,"","");}
        /** 生成说明只参与检索；回答与引用核验的事实依据来自原文或相邻原文。 */
        public String modelText(){return parentContext==null||parentContext.isBlank()?text:parentContext;}
    }
    @org.springframework.beans.factory.annotation.Autowired @Qualifier("paismartRecallExecutor") private Executor recallExecutor=Runnable::run;
    public record SearchResult(List<Evidence> rows,String scoreType,List<String> degradation,int total){}
    private record Hit(String id,double score){}
    private final JdbcTemplate db;private final ControlledHttp http;private final IntelligenceSettings settings;private final ProviderService providers;private final ModelGateway models;private final SpaceService spaces;private final DocumentService documents;
    public SearchService(@Qualifier("paismartJdbc")JdbcTemplate db,ControlledHttp http,IntelligenceSettings settings,ProviderService providers,ModelGateway models,SpaceService spaces,DocumentService documents){this.db=db;this.http=http;this.settings=settings;this.providers=providers;this.models=models;this.spaces=spaces;this.documents=documents;}
    public String es(){String u=settings.text("elasticsearch.endpoint","");if(u.isBlank())throw new IllegalStateException("PROVIDER_UNAVAILABLE:ES未配置");return u.replaceAll("/+$","");}
    public String esCredential(){String ref=settings.text("elasticsearch.secret-ref","");return ref.isBlank()?null:"ApiKey "+settings.secret(ref);}
    public Map<String,Object> esRequest(String method,String path,Object body){return esRequest(method,path,body,90);}
    public Map<String,Object> esRequest(String method,String path,Object body,int timeout){if(!path.startsWith("/")||path.contains("..")||path.contains("?"))throw new IllegalArgumentException("ES请求路径无效");return http.json(method,es()+path,esCredential(),body,timeout);}
    public Map<String,Object> activeIndex(){if(db.queryForObject("SELECT COUNT(*) FROM ps_index_switch WHERE state IN ('PREPARED','ALIAS_CHANGED','RECOVERY_REQUIRED')",Long.class)>0)throw new IllegalStateException("索引切换状态待恢复，当前查询暂不可用");List<Map<String,Object>> rows=db.queryForList("SELECT * FROM ps_index_revision WHERE state='ACTIVE'");if(rows.size()!=1)throw new IllegalStateException("PROVIDER_UNAVAILABLE:知识索引未就绪");return rows.get(0);}
    public SearchResult search(SpaceService.Scope scope,String query,int topK,String spaceId){return searchAt(scope,query,topK,spaceId,activeIndex());}
    public SearchResult searchAt(SpaceService.Scope scope,String query,int topK,String spaceId,Map<String,Object> index) {
        if(query==null||query.isBlank()||query.length()>4000||topK<1||topK>20)throw new IllegalArgumentException("查询或TopK无效");
        members.requireEligible(scope.userId());String indexName=Values.text(index.get("index_name"));if(!settings.enabled("contextual-enabled"))requireRawProjection(indexName);Map<String,Object> filter=filter(scope,spaceId);List<Double> vector;
        try {ProviderService.Provider embedding=providers.fromSnapshot(Values.text(index.get("provider_snapshot")));vector=models.embed(scope.userId(),"search:"+Values.id(),List.of(query),embedding).vectors().get(0);}
        catch(RuntimeException failure){members.requireEligible(scope.userId());return textOnly(scope,indexName,query,topK,filter,"Embedding不可用，降级为带权限的BM25检索");}
        int recall=Math.max(topK,Math.min(setting("hybrid.max-recall-window",300,1,1000),topK*setting("hybrid.recall-multiplier",30,1,100)));
        int timeout=setting("hybrid.recall-timeout-seconds",10,1,90);List<Hit> lexical,semantic;
        Map<String,Object> lexicalBody=Map.of("size",recall,"query",Map.of("bool",Map.of("must",List.of(keyword(query)),"filter",List.of(filter))));
        Map<String,Object> semanticBody=Map.of("size",recall,"knn",Map.of("field","embedding","query_vector",vector,"k",recall,"num_candidates",Math.min(10000,recall*setting("hybrid.knn-num-candidates-multiplier",2,1,30)),"filter",filter));
        FutureTask<List<Hit>> bm25=new FutureTask<>(()->hits(esRequest("POST","/"+indexName+"/_search",lexicalBody,timeout)));
        FutureTask<List<Hit>> knn=new FutureTask<>(()->hits(esRequest("POST","/"+indexName+"/_search",semanticBody,timeout)));
        try {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout);recallExecutor.execute(bm25);recallExecutor.execute(knn);lexical=bm25.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);semantic=knn.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(Exception failure){bm25.cancel(true);knn.cancel(true);if(failure instanceof InterruptedException){Thread.currentThread().interrupt();throw new IllegalStateException("检索被中断",failure);}members.requireEligible(scope.userId());return textOnly(scope,indexName,query,topK,filter,"混合召回不可用，降级为带权限的BM25检索");}
        List<RrfFusion.Fused<Hit>> fused=RrfFusion.fuse(setting("hybrid.rrf-k",60,1,1000),Hit::id,lexical,semantic);List<Evidence> candidates=new ArrayList<>();
        int maxCandidates=Math.max(topK,setting("rerank.max-candidates",50,1,300));for(var hit:fused){Evidence e=authorized(scope,hit.key(),hit.score(),"RRF");if(e!=null)candidates.add(e);if(candidates.size()>=maxCandidates)break;}
        String scoreType="RRF";List<String> degradation=new ArrayList<>();
        if(settings.enabled("rerank-enabled")&&!candidates.isEmpty())try {
            int maxChars=setting("rerank.max-document-characters",2000,100,16000);Map<String,Object> response=models.rerank(scope.userId(),"rerank:"+Values.id(),query,candidates.stream().map(e->limit(settings.enabled("contextual-enabled")&&!e.context().isBlank()?e.context()+"\n\n"+e.text():e.text(),maxChars)).toList(),providers.active("RERANK"));
            List<Evidence> reordered=new ArrayList<>();Set<Integer> used=new HashSet<>();double threshold=Double.parseDouble(settings.text("rerank-threshold","0.2"));if(!Double.isFinite(threshold)||threshold<0||threshold>1)throw new IllegalArgumentException("重排阈值无效");
            for(Object item:Values.list(response.get("results"))){Map<String,Object> row=Values.map(item);int n=Values.range(row.get("index"),-1,0,candidates.size()-1);double relevance=((Number)row.get("relevance_score")).doubleValue();if(!Double.isFinite(relevance)||!used.add(n))throw new IllegalStateException("重排协议异常");if(relevance>=threshold)reordered.add(score(candidates.get(n),relevance,"RERANK"));}
            if(used.size()!=candidates.size())throw new IllegalStateException("重排数量不一致");reordered.sort(Comparator.comparingDouble(Evidence::score).reversed());candidates=reordered;scoreType="RERANK";
        }catch(RuntimeException failure){degradation.add("重排不可用，保留RRF排序；相关性阈值未用于RRF分数");}
        candidates=candidates.subList(0,Math.min(topK,candidates.size()));return new SearchResult(List.copyOf(candidates),scoreType,List.copyOf(degradation),candidates.size());
    }
    private int setting(String key,int fallback,int min,int max){return Values.range(settings.number(key,fallback),fallback,min,max);}
    private Map<String,Object> keyword(String query){return Map.of("match",Map.of(settings.enabled("contextual-enabled")?"search_text":"text_content",query));}
    /** 旧投影缺独立原文字段时要求完整新 revision，禁止把未知字段的零命中冒作无知识。 */
    public void requireRawProjection(String index){Map<String,Object> response=esRequest("GET","/"+index+"/_mapping",null,setting("hybrid.recall-timeout-seconds",10,1,90));Map<String,Object> properties=Values.map(Values.map(Values.map(response.get(index)).get("mappings")).get("properties"));if(!"text".equals(Values.map(properties.get("text_content")).get("type")))throw new IllegalStateException("INDEX_REBUILD_REQUIRED:当前索引缺独立原文字段，请完整重建新索引版本后查询");}
    private String limit(String value,int max){return value.length()<=max?value:value.substring(0,max);}
    private SearchResult textOnly(SpaceService.Scope scope,String index,String query,int top,Map<String,Object> filter,String reason) {
        try {List<Evidence> result=new ArrayList<>();for(var hit:hits(esRequest("POST","/"+index+"/_search",Map.of("size",top,"min_score",.3,"query",Map.of("bool",Map.of("must",List.of(keyword(query)),"filter",List.of(filter)))),setting("hybrid.recall-timeout-seconds",10,1,90)))){Evidence e=authorized(scope,hit.id(),hit.score(),"TEXT_ONLY");if(e!=null)result.add(e);}return new SearchResult(List.copyOf(result),"TEXT_ONLY",List.of(reason),result.size());}
        catch(RuntimeException unavailable){throw new IllegalStateException("SEARCH_UNAVAILABLE:BM25检索也失败，无法查询知识库",unavailable);}
    }
    private Map<String,Object> filter(SpaceService.Scope scope,String spaceId){List<Object> clauses=new ArrayList<>();clauses.add(Map.of("term",Map.of("state","READY")));if(!scope.administrator()){List<Object> allowed=new ArrayList<>();allowed.add(Map.of("term",Map.of("owner_id",scope.userId())));allowed.add(Map.of("term",Map.of("visibility","PUBLIC")));Set<String> ids=spaces.visibleSpaceIds(scope);if(!ids.isEmpty())allowed.add(Map.of("bool",Map.of("filter",List.of(Map.of("term",Map.of("visibility","ORGANIZATION")),Map.of("terms",Map.of("space_id",ids))))));clauses.add(Map.of("bool",Map.of("should",allowed,"minimum_should_match",1)));}if(spaceId!=null&&!spaceId.isBlank())clauses.add(Map.of("term",Map.of("space_id",spaceId)));return Map.of("bool",Map.of("filter",clauses));}
    private List<Hit> hits(Map<String,Object> response){List<Hit> out=new ArrayList<>();for(Object row:Values.list(Values.map(response.get("hits")).get("hits"))){Map<String,Object> m=Values.map(row);out.add(new Hit(Values.text(m.get("_id")),m.get("_score") instanceof Number n?n.doubleValue():0));}return out;}
    public Evidence authorized(SpaceService.Scope scope,String chunkId,double score,String scoreType){List<Map<String,Object>> rows=db.queryForList("SELECT * FROM ps_document_chunk WHERE id=?",chunkId);if(rows.isEmpty())return null;Map<String,Object> c=rows.get(0);try{var d=documents.getReadable(scope,Values.text(c.get("document_id")));if(d.contentVersion()!=Values.number(c.get("content_version"),0)||!"READY".equals(d.state()))return null;String context=Values.text(c.get("context_text"));return new Evidence(chunkId,d.id(),d.contentVersion(),(int)Values.number(c.get("chunk_no"),0),c.get("page_no")==null?null:(int)Values.number(c.get("page_no"),0),d.title(),Values.text(c.get("text_content")),Values.text(c.get("anchor_text")),context,score,scoreType);}catch(com.ruoyi.common.exception.ServiceException denied){return null;}}
    private Evidence score(Evidence e,double score,String type){return new Evidence(e.id(),e.documentId(),e.contentVersion(),e.chunkNo(),e.page(),e.title(),e.text(),e.anchor(),e.context(),score,type);}
    /** 每个命中保留自己的小块字段；同一父块复用，预算用尽后仍保留剩余命中。 */
    public List<Evidence> expand(SpaceService.Scope scope,List<Evidence> hits) {
        if(hits==null||hits.isEmpty()||!Boolean.parseBoolean(settings.text("parent-context.enabled","true")))return hits;
        int remaining=setting("parent-context.max-total-characters",6000,0,64000),perHit=setting("parent-context.max-characters-per-hit",1500,0,16000),neighbors=setting("parent-context.neighbour-chunks",1,0,10);
        List<ParentBlock> blocks=new ArrayList<>();List<Evidence> result=new ArrayList<>(hits.size());
        for(Evidence hit:hits) {
            if(authorized(scope,hit.id(),hit.score(),hit.scoreType())==null)continue;
            ParentBlock shared=blocks.stream().filter(p->p.covers(hit)).findFirst().orElse(null);
            if(shared==null&&remaining>0&&neighbors>0)try{shared=buildParent(scope,hit,neighbors,Math.min(remaining,perHit));if(shared!=null){blocks.add(shared);remaining-=shared.text().length();}}catch(RuntimeException unavailable){/* 父块失败只使用已授权的小块。 */}
            result.add(shared==null?hit:new Evidence(hit.id(),hit.documentId(),hit.contentVersion(),hit.chunkNo(),hit.page(),hit.title(),hit.text(),hit.anchor(),hit.context(),hit.score(),hit.scoreType(),shared.text(),shared.key()));
        }
        return List.copyOf(result);
    }
    private ParentBlock buildParent(SpaceService.Scope scope,Evidence hit,int neighbors,int cap) {
        List<String> ids=db.queryForList("SELECT id FROM ps_document_chunk WHERE document_id=? AND content_version=? AND chunk_no BETWEEN ? AND ? ORDER BY chunk_no",String.class,hit.documentId(),hit.contentVersion(),Math.max(0,hit.chunkNo()-neighbors),hit.chunkNo()+neighbors);
        List<Evidence> window=new ArrayList<>();for(String id:ids){Evidence e=authorized(scope,id,0,"CONTEXT");if(e!=null)window.add(e);}
        int anchor=-1;for(int i=0;i<window.size();i++)if(window.get(i).id().equals(hit.id()))anchor=i;
        if(anchor<0||window.get(anchor).text().length()>cap)return null;
        int lo=anchor,hi=anchor;boolean before=true,after=true;
        while(before||after){if(after){after=hi+1<window.size()&&TextChunker.merge(window.subList(lo,hi+2).stream().map(Evidence::text).toList()).length()<=cap;if(after)hi++;}if(before){before=lo>0&&TextChunker.merge(window.subList(lo-1,hi+1).stream().map(Evidence::text).toList()).length()<=cap;if(before)lo--;}}
        if(lo==hi)return null;String text=TextChunker.merge(window.subList(lo,hi+1).stream().map(Evidence::text).toList());return new ParentBlock(hit.documentId(),hit.contentVersion(),window.get(lo).chunkNo(),window.get(hi).chunkNo(),text);
    }
    private record ParentBlock(String document,int version,int from,int to,String text) {
        boolean covers(Evidence hit){return document.equals(hit.documentId())&&version==hit.contentVersion()&&hit.chunkNo()>=from&&hit.chunkNo()<=to;}
        String key(){return document+":"+version+"#"+from+"-"+to;}
    }
    /** 绑定组织的会话可被明确授权的该空间管理者审计，工具资料不能夹带私人文档。 */
    public List<Evidence> sharedEvidence(SpaceService.Scope scope,List<Evidence> evidence,String space){if(space==null||space.isBlank())return evidence;return evidence.stream().filter(e->{var doc=documents.getReadable(scope,e.documentId());return space.equals(doc.spaceId())&&Set.of("ORGANIZATION","PUBLIC").contains(doc.visibility());}).toList();}
    public Map<String,Object> statistics(SpaceService.Scope scope){return countStatistics(scope,null,false);}
    public Map<String,Object> statistics(SpaceService.Scope scope,String space){return countStatistics(scope,space,true);}
    // 文档与块的VARCHAR(36) ID按完整最多144字节比较，兼容原库表排序规则且不改变可见范围。
    private Map<String,Object> countStatistics(SpaceService.Scope scope,String space,boolean organization){members.requireEligible(scope.userId());StringBuilder where=new StringBuilder(" WHERE d.deleted_at IS NULL");List<Object> parameters=new ArrayList<>();if(!scope.administrator()){where.append(" AND (d.user_id=? OR d.visibility='PUBLIC'");parameters.add(scope.userId());Set<String> allowed=spaces.visibleSpaceIds(scope);if(!allowed.isEmpty()){where.append(" OR (d.visibility='ORGANIZATION' AND d.space_id IN (").append(String.join(",",Collections.nCopies(allowed.size(),"?"))).append("))");parameters.addAll(allowed);}where.append(')');}if(space!=null&&!space.isBlank()){where.append(" AND d.space_id=?");parameters.add(space);}if(organization)where.append(" AND d.visibility IN ('ORGANIZATION','PUBLIC')");long documents=db.queryForObject("SELECT COUNT(*) FROM ps_document d"+where,Long.class,parameters.toArray());long chunks=db.queryForObject("SELECT COUNT(*) FROM ps_document d JOIN ps_document_chunk c ON CAST(c.document_id AS BINARY(144))=CAST(d.id AS BINARY(144)) AND c.content_version=d.version"+where+" AND d.status='READY'",Long.class,parameters.toArray());java.sql.Timestamp latest=db.queryForObject("SELECT MAX(d.updated_at) FROM ps_document d"+where,(rs,row)->rs.getTimestamp(1),parameters.toArray());Map<String,Object> result=new LinkedHashMap<>();result.put("visibleDocuments",documents);result.put("documentCount",documents);result.put("countedDocuments",documents);result.put("countedChunks",chunks);result.put("chunkCountComplete",true);result.put("latestUpdatedAt",latest==null?null:latest.toLocalDateTime());result.put("scope",organization?"SELECTED_ORGANIZATION":"VISIBLE_CURRENT_VERSION");result.put("authority","PAISMART_MYSQL");result.put("source","CURRENT_PERMISSION_SCOPE");return result;}
    public List<Map<String,Object>> conflictCandidates(SpaceService.Scope scope,String exclude,List<Double> vector){
        members.requireEligible(scope.userId());Map<String,Object> revision=activeIndex();
        int neighbors=setting("conflict.neighbors-per-chunk",3,1,30);double minimum=Double.parseDouble(settings.text("conflict.similarity-threshold","0.9"));
        if(!Double.isFinite(minimum)||minimum<0||minimum>1)throw new IllegalArgumentException("冲突相似度阈值须在0到1之间");
        Map<String,Object> allowed=Map.of("bool",Map.of("filter",List.of(filter(scope,null),Map.of("term",Map.of("embedding_revision",revision.get("id")))),"must_not",List.of(Map.of("term",Map.of("document_id",exclude)))));
        List<Hit> hits=hits(esRequest("POST","/"+revision.get("index_name")+"/_search",Map.of("size",neighbors,"knn",Map.of("field","embedding","query_vector",vector,"k",neighbors,"num_candidates",Math.max(100,neighbors*20),"filter",allowed))));
        List<Map<String,Object>> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(var hit:hits){
            double cosine=cosineFromScore(hit.score());if(!Double.isFinite(cosine)||cosine<minimum||!seen.add(hit.id()))continue;
            Evidence evidence=authorized(scope,hit.id(),hit.score(),"VECTOR");if(evidence==null||exclude.equals(evidence.documentId()))continue;
            for(var row:db.queryForList("SELECT * FROM ps_document_chunk WHERE id=? AND embedding_revision=?",evidence.id(),revision.get("id"))){row.put("cosineSimilarity",cosine);result.add(row);}
        }
        return result;
    }
    static double cosineFromScore(Double score){return score==null?0:2*score-1;}
}


