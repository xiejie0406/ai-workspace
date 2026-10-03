package com.ruoyi.paiflow.mapper;
import com.ruoyi.paiflow.domain.ModelRecord;
import com.ruoyi.paiflow.domain.ModelVersionRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;
public interface ModelMapper {
    ModelRecord find(@Param("id")long id);
    ModelRecord lock(@Param("id")long id);
    long count(@Param("spaceId")long spaceId,@Param("search")String search,@Param("type")Integer type);
    List<ModelRecord> list(@Param("spaceId")long spaceId,@Param("search")String search,@Param("type")Integer type,@Param("offset")long offset,@Param("limit")int limit);
    long countName(@Param("spaceId")long spaceId,@Param("name")String name,@Param("excludeId")Long excludeId);
    int insert(ModelRecord row);
    int update(ModelRecord row);
    int delete(@Param("id")long id,@Param("spaceId")long spaceId,@Param("revision")long revision,@Param("actor")String actor);
    int insertVersion(ModelVersionRecord row);
    int selectVersion(@Param("id")long id,@Param("revision")long revision,@Param("versionId")long versionId);
    ModelVersionRecord version(@Param("id")long id);
    long countVersions(@Param("modelId")long modelId);
    List<ModelVersionRecord> versions(@Param("modelId")long modelId,@Param("offset")long offset,@Param("limit")int limit);
}
