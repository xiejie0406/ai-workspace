package com.ruoyi.fashion.infrastructure.persistence.image;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.application.proposal.*;
import tools.jackson.databind.ObjectMapper;
@Repository @FashionModuleEnabled
public class JdbcProposalImageRepository implements ProposalImageRepository {
    private final NamedParameterJdbcTemplate jdbc; private final ObjectMapper mapper;
    public JdbcProposalImageRepository(@Qualifier("fashionJdbcTemplate") NamedParameterJdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    private List<ProposalImageTask> query(String where,Map<String,?> args) {
        return jdbc.query("select * from fq_proposal_image_task where "+where,args,(rs,n)->new ProposalImageTask(rs.getString("id"),rs.getString("pool_id"),rs.getLong("owner_id"),rs.getLong("draft_version"),rs.getString("combo_id"),rs.getString("input_hash"),mapper.readTree(rs.getString("input_json")),rs.getString("request_key"),rs.getString("status"),rs.getString("provider_task_id"),mapper.readTree(rs.getString("result_json")==null?"[]":rs.getString("result_json")),rs.getString("error_message"),mapper.readTree(rs.getString("review_json")==null?"{}":rs.getString("review_json")),rs.getLong("row_version")));
    }
    public Optional<ProposalImageTask> find(String id,long owner){return query("id=:id and owner_id=:owner",Map.of("id",id,"owner",owner)).stream().findFirst();}
    public Optional<ProposalImageTask> request(String key,long owner){return query("request_key=:key and owner_id=:owner",Map.of("key",key,"owner",owner)).stream().findFirst();}
    public List<ProposalImageTask> list(String poolId,long owner){return query("pool_id=:pool and owner_id=:owner order by create_time desc",Map.of("pool",poolId,"owner",owner));}
    private Map<String,Object> params(ProposalImageTask t){var m=new HashMap<String,Object>();m.put("id",t.id());m.put("pool",t.poolId());m.put("owner",t.ownerId());m.put("draft",t.draftVersion());m.put("combo",t.combinationId());m.put("hash",t.inputHash());m.put("inputs",mapper.writeValueAsString(t.inputs()));m.put("key",t.requestKey());m.put("status",t.status());m.put("provider",t.providerTaskId());m.put("results",mapper.writeValueAsString(t.results()));m.put("error",t.errorMessage());m.put("review",mapper.writeValueAsString(t.review()));return m;}
    public void insert(ProposalImageTask t){jdbc.update("insert into fq_proposal_image_task(id,pool_id,owner_id,draft_version,combo_id,input_hash,input_json,request_key,status,provider_task_id,result_json,error_message,review_json,row_version,create_time,update_time) values(:id,:pool,:owner,:draft,:combo,:hash,:inputs,:key,:status,:provider,:results,:error,:review,1,current_timestamp,current_timestamp)",params(t));}
    public boolean update(ProposalImageTask t,long expected){var m=params(t);m.put("version",expected);return jdbc.update("update fq_proposal_image_task set status=:status,provider_task_id=:provider,result_json=:results,error_message=:error,review_json=:review,row_version=row_version+1,update_time=current_timestamp where id=:id and owner_id=:owner and row_version=:version",m)==1;}
}
