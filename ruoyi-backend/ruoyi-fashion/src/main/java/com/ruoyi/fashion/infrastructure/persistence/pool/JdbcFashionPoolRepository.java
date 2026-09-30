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
 public List<Map<String,Object>> list(long owner){return jdbc.query("select id,title,create_time from fq_selection_pool where owner_id=:owner order by create_time desc,id limit 100",Map.of("owner",owner),(rs,n)->Map.of("id",rs.getString(1),"title",rs.getString(2),"createdAt",rs.getTimestamp(3).toInstant().toString()));}
 public Selection selection(String id,long owner){List<String> rows=jdbc.query("select selection_json from fq_selection_draft where pool_id=:id and owner_id=:owner",Map.of("id",id,"owner",owner),(rs,n)->rs.getString(1));return rows.isEmpty()?new Selection(id,0,false,List.of(),List.of(),Map.of(),List.of(),Map.of()):json.readValue(rows.get(0),Selection.class);}
 public Selection save(String id,long owner,Selection s){Selection next=new Selection(id,s.rowVersion()+1,s.all(),s.selectedIds(),s.excludedIds(),s.names(),s.orderedIds(),s.variantSelections());Map<String,Object> params=Map.of("id",id,"owner",owner,"version",s.rowVersion(),"next",next.rowVersion(),"json",json.writeValueAsString(next),"time",Timestamp.from(Instant.now()));
  if(s.rowVersion()==0){try{jdbc.update("insert into fq_selection_draft(pool_id,owner_id,row_version,selection_json,update_time) values(:id,:owner,:next,:json,:time)",params);}catch(org.springframework.dao.DuplicateKeyException e){throw new ServiceException("方案已被修改，请重新加载后保存");}}
  else if(jdbc.update("update fq_selection_draft set row_version=:next,selection_json=:json,update_time=:time where pool_id=:id and owner_id=:owner and row_version=:version",params)!=1)throw new ServiceException("方案已被修改，请重新加载后保存");return next;
 }
}
