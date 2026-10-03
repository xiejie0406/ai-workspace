package com.ruoyi.fashion.infrastructure.persistence.delivery;
import java.util.*;
import com.ruoyi.fashion.application.proposal.ProposalDeliveryRepository;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
@Repository @FashionModuleEnabled
public class JdbcProposalDeliveryRepository implements ProposalDeliveryRepository {
    private final NamedParameterJdbcTemplate jdbc;private final ObjectMapper json;
    public JdbcProposalDeliveryRepository(@Qualifier("fashionJdbcTemplate") NamedParameterJdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    private List<Entry> query(String where,Map<String,?> params){return jdbc.query("select * from fq_proposal_delivery where "+where,params,(rs,n)->new Entry(rs.getString("id"),rs.getLong("owner_id"),rs.getString("pool_id"),rs.getLong("draft_version"),rs.getString("request_key"),rs.getString("title"),rs.getString("status"),json.readTree(rs.getString("snapshot_json")),rs.getString("file_key"),rs.getString("file_hash"),rs.getObject("file_size")==null?null:rs.getLong("file_size"),rs.getInt("page_count"),rs.getLong("download_count"),rs.getTimestamp("create_time").toInstant(),rs.getString("error_message")));}
    public Optional<Entry> find(String id,long owner){return query("id=:id and owner_id=:owner",Map.of("id",id,"owner",owner)).stream().findFirst();}
    public Optional<Entry> request(String key,long owner){return query("request_key=:key and owner_id=:owner",Map.of("key",key,"owner",owner)).stream().findFirst();}
    public List<Entry> list(long owner,int page,int pageSize){return query("owner_id=:owner order by create_time desc,id desc limit :limit offset :offset",Map.of("owner",owner,"limit",pageSize,"offset",(long)(page-1)*pageSize));}
    public long count(long owner){Long n=jdbc.queryForObject("select count(*) from fq_proposal_delivery where owner_id=:owner",Map.of("owner",owner),Long.class);return n==null?0:n;}
    public Map<String,Map<String,Object>> listContext(Set<String> ids,long owner){
        if(ids.isEmpty())return Map.of();var result=new LinkedHashMap<String,Map<String,Object>>();
        jdbc.query("select d.id,p.title poolTitle,coalesce(nullif(u.nick_name,''),u.user_name) ownerName,d.update_time from fq_proposal_delivery d join fq_selection_pool p on p.id=d.pool_id and p.owner_id=d.owner_id left join sys_user u on u.user_id=d.owner_id where d.owner_id=:owner and d.id in (:ids)",Map.of("owner",owner,"ids",ids),(org.springframework.jdbc.core.RowCallbackHandler)rs->{var row=new LinkedHashMap<String,Object>();row.put("poolTitle",rs.getString("poolTitle"));row.put("ownerName",rs.getString("ownerName"));row.put("updatedAt",rs.getTimestamp("update_time").toInstant().toString());result.put(rs.getString("id"),row);});return result;
    }
    private Map<String,Object> params(Entry e){var p=new HashMap<String,Object>();p.put("id",e.id());p.put("owner",e.owner());p.put("pool",e.poolId());p.put("version",e.draftVersion());p.put("key",e.requestKey());p.put("title",e.title());p.put("status",e.status());p.put("snapshot",json.writeValueAsString(e.snapshot()));p.put("file",e.fileKey());p.put("hash",e.fileHash());p.put("size",e.fileSize());p.put("pages",e.pageCount());p.put("downloads",e.downloadCount());p.put("created",java.sql.Timestamp.from(e.createdAt()));p.put("error",e.errorMessage());return p;}
    public void insert(Entry e){jdbc.update("insert into fq_proposal_delivery(id,owner_id,pool_id,draft_version,request_key,title,status,snapshot_json,file_key,file_hash,file_size,page_count,download_count,create_time,update_time,error_message) values(:id,:owner,:pool,:version,:key,:title,:status,:snapshot,:file,:hash,:size,:pages,:downloads,:created,:created,:error)",params(e));}
    public boolean finish(Entry e){return jdbc.update("update fq_proposal_delivery set status=:status,snapshot_json=:snapshot,file_key=:file,file_hash=:hash,file_size=:size,page_count=:pages,error_message=:error,update_time=current_timestamp where id=:id and owner_id=:owner and status='generating'",params(e))==1;}
    public boolean incrementDownload(String id,long owner){return jdbc.update("update fq_proposal_delivery set download_count=download_count+1,update_time=current_timestamp where id=:id and owner_id=:owner and status='success'",Map.of("id",id,"owner",owner))==1;}
}
