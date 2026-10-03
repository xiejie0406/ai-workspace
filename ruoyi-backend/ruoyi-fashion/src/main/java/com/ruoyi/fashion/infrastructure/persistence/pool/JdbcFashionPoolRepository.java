package com.ruoyi.fashion.infrastructure.persistence.pool;
import static com.ruoyi.fashion.application.pool.PoolModels.*;
import java.util.*;
import java.sql.Timestamp;
import java.time.Instant;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository @FashionModuleEnabled
public class JdbcFashionPoolRepository {
 private final NamedParameterJdbcTemplate jdbc; private final ObjectMapper json;
 public JdbcFashionPoolRepository(@Qualifier("fashionJdbcTemplate") NamedParameterJdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 public void insert(Pool pool,long owner) {String snapshot=json.writeValueAsString(pool);if(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>20*1024*1024)throw new ServiceException("候选商品图片及属性快照超过20MiB，请缩小分类或商品范围后重试");jdbc.update("insert into fq_selection_pool(id,owner_id,title,request_json,snapshot_json,create_time) values(:id,:owner,:title,:request,:snapshot,:time)",Map.of("id",pool.id(),"owner",owner,"title",pool.title(),"request",json.writeValueAsString(pool.request()),"snapshot",snapshot,"time",Timestamp.from(Instant.parse(pool.createdAt()))));}
 public Pool get(String id,long owner) {List<String> rows=jdbc.query("select snapshot_json from fq_selection_pool where id=:id and owner_id=:owner",Map.of("id",id,"owner",owner),(rs,n)->rs.getString(1));if(rows.isEmpty())throw new ServiceException("方案池不存在或无权访问");return json.readValue(rows.get(0),Pool.class);}
 public List<Map<String,Object>> list(long owner){return jdbc.query("select p.id,p.title,p.create_time,p.request_json,p.snapshot_json,d.selection_json,d.update_time,d.row_version,coalesce(nullif(u.nick_name,''),u.user_name) owner_name from fq_selection_pool p left join fq_selection_draft d on d.pool_id=p.id and d.owner_id=p.owner_id left join sys_user u on u.user_id=p.owner_id where p.owner_id=:owner order by p.create_time desc,p.id limit 100",Map.of("owner",owner),(rs,n)->{
  var pool=json.readTree(rs.getString("snapshot_json"));var request=json.readTree(rs.getString("request_json"));var selection=rs.getString("selection_json")==null?null:json.readTree(rs.getString("selection_json"));
  var row=new LinkedHashMap<String,Object>();row.put("id",rs.getString("id"));row.put("title",rs.getString("title"));row.put("ownerId",Long.toString(owner));row.put("ownerName",rs.getString("owner_name"));row.put("createdAt",rs.getTimestamp("create_time").toInstant().toString());
  row.put("activeGroupCount",request.path("groups").size());row.put("quantity",request.path("totalConditions").path("quantity").isNumber()?request.path("totalConditions").path("quantity").asLong():null);row.put("budgetMin",request.path("totalMin").isNumber()?request.path("totalMin").decimalValue():null);row.put("budgetMax",request.path("totalMax").isNumber()?request.path("totalMax").decimalValue():null);row.put("priceBasis",request.path("priceBasis").isTextual()?request.path("priceBasis").asText():null);
  long total=pool.path("total").asLong();row.put("totalCount",total);row.put("rawCount",pool.path("rawCount").asLong());long selected=0;
  if(selection!=null){Set<String> exclusions=new HashSet<>();selection.path("excludedIds").forEach(x->exclusions.add(x.asText()));if(selection.path("all").asBoolean())selected=Math.max(0,total-exclusions.size());else{Set<String> included=new HashSet<>();selection.path("selectedIds").forEach(x->included.add(x.asText()));included.removeAll(exclusions);selected=included.size();}}
  row.put("selectedCount",selected);row.put("draftVersion",rs.getObject("row_version")==null?0:rs.getLong("row_version"));Timestamp updated=rs.getTimestamp("update_time");row.put("updatedAt",updated==null?null:updated.toInstant().toString());row.put("stage",selection==null?"generated":"draft_saved");return row;
 });}
 public Selection selection(String id,long owner){List<String> rows=jdbc.query("select selection_json from fq_selection_draft where pool_id=:id and owner_id=:owner",Map.of("id",id,"owner",owner),(rs,n)->rs.getString(1));return rows.isEmpty()?new Selection(id,0,false,List.of(),List.of(),Map.of(),List.of(),Map.of()):json.readValue(rows.get(0),Selection.class);}
 public Selection save(String id,long owner,Selection s){Selection next=new Selection(id,s.rowVersion()+1,s.all(),s.selectedIds(),s.excludedIds(),s.names(),s.orderedIds(),s.variantSelections());Map<String,Object> params=Map.of("id",id,"owner",owner,"version",s.rowVersion(),"next",next.rowVersion(),"json",json.writeValueAsString(next),"time",Timestamp.from(Instant.now()));
  if(s.rowVersion()==0){try{jdbc.update("insert into fq_selection_draft(pool_id,owner_id,row_version,selection_json,update_time) values(:id,:owner,:next,:json,:time)",params);}catch(org.springframework.dao.DuplicateKeyException e){throw new ServiceException("方案已被修改，请重新加载后保存");}}
  else if(jdbc.update("update fq_selection_draft set row_version=:next,selection_json=:json,update_time=:time where pool_id=:id and owner_id=:owner and row_version=:version",params)!=1)throw new ServiceException("方案已被修改，请重新加载后保存");return next;
 }
}
