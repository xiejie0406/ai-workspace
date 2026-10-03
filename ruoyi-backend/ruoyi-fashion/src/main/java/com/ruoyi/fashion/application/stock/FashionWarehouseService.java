package com.ruoyi.fashion.application.stock;

import java.util.*;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.application.settings.FashionSettingsService;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLock;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysDictDataService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 仓库只维护平台字典，库存事实仍属于 fq_stock。 */
@Service @FashionModuleEnabled
public class FashionWarehouseService {
    public static final String TYPE = "fashion_warehouse";
    public record Write(String code, String name, String status, Long sort, String remark) {}
    private final ISysDictDataService dictionaries;
    private final ISysConfigService config;
    private final FashionSettingsService settings;
    private final NamedParameterJdbcTemplate jdbc;
    private final FashionCatalogWriteLock lock;
    public FashionWarehouseService(ISysDictDataService dictionaries, ISysConfigService config,
            FashionSettingsService settings, @Qualifier("fashionJdbcTemplate") NamedParameterJdbcTemplate jdbc,
            FashionCatalogWriteLock lock) {
        this.dictionaries=dictionaries; this.config=config; this.settings=settings; this.jdbc=jdbc; this.lock=lock;
    }
    public List<Map<String,Object>> list() {
        String defaultCode=defaultCode();
        Map<String,Map<String,Object>> summaries=new HashMap<>();
        jdbc.queryForList("select warehouse_code,count(distinct product_id) stockSkuCount,max(as_of) lastStockAsOf from fq_stock group by warehouse_code",Map.of())
                .forEach(row->summaries.put(Objects.toString(row.get("warehouse_code")),row));
        return entries().stream().map(entry -> {
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("code",entry.getDictValue());row.put("name",entry.getDictLabel());
            row.put("status","0".equals(entry.getStatus())?"active":"inactive");
            row.put("isDefault",defaultCode.equals(entry.getDictValue()));row.put("sort",entry.getDictSort());row.put("remark",entry.getRemark());
            Map<String,Object> stock=summaries.getOrDefault(entry.getDictValue(),Map.of());
            row.put("stockSkuCount",stock.getOrDefault("stockSkuCount",0L));
            Object time=stock.get("lastStockAsOf");row.put("lastStockAsOf",time instanceof java.sql.Timestamp t?t.toInstant().toString():time);
            return row;
        }).toList();
    }
    public List<Map<String,String>> activeOptions() {
        return entries().stream().filter(e -> "0".equals(e.getStatus()))
                .map(e -> Map.of("code",e.getDictValue(),"name",e.getDictLabel())).toList();
    }
    public String activeDefaultCode(){String value=defaultCode();return entries().stream().anyMatch(e->value.equals(e.getDictValue())&&"0".equals(e.getStatus()))?value:"";}
    public void requireActive(String code) {
        if(code==null || entries().stream().noneMatch(e->code.equals(e.getDictValue())&&"0".equals(e.getStatus())))
            throw new ServiceException("仓库不存在或已停用");
    }
    @Transactional(transactionManager="fashionTransactionManager")
    public void create(Write request,String operator) {
        lock.executeLocked(10,()->{validate(request);if(entries().stream().anyMatch(e->request.code().equals(e.getDictValue())))throw new ServiceException("仓库编码已存在");
            SysDictData entry=new SysDictData();entry.setDictType(TYPE);entry.setDictValue(request.code());entry.setIsDefault("N");entry.setCreateBy(operator);
            apply(entry,request);if(dictionaries.insertDictData(entry)!=1)throw new ServiceException("新增仓库失败");return true;});
    }
    @Transactional(transactionManager="fashionTransactionManager")
    public void update(String code,Write request,String operator) {
        lock.executeLocked(10,()->{validate(request);if(!code.equals(request.code()))throw new ServiceException("仓库编码创建后不能修改");
            SysDictData entry=entry(code);checkDisable(code,request.status());apply(entry,request);entry.setUpdateBy(operator);
            if(dictionaries.updateDictData(entry)!=1)throw new ServiceException("更新仓库失败");return true;});
    }
    @Transactional(transactionManager="fashionTransactionManager")
    public void status(String code,String status,String operator) {
        lock.executeLocked(10,()->{if(!Set.of("active","inactive").contains(status==null?"":status))throw new ServiceException("仓库状态无效");
            SysDictData entry=entry(code);checkDisable(code,status);entry.setStatus("active".equals(status)?"0":"1");entry.setUpdateBy(operator);
            if(dictionaries.updateDictData(entry)!=1)throw new ServiceException("更新仓库状态失败");return true;});
    }
    @Transactional(transactionManager="fashionTransactionManager")
    public void makeDefault(String code,String operator) {
        lock.executeLocked(10,()->{requireActive(code);settings.update("fashion.catalog.defaultWarehouse",code,operator);return true;});
    }
    private List<SysDictData> entries() {SysDictData query=new SysDictData();query.setDictType(TYPE);List<SysDictData> result=dictionaries.selectDictDataList(query);return result==null?List.of():result;}
    private SysDictData entry(String code){return entries().stream().filter(e->code.equals(e.getDictValue())).findFirst().orElseThrow(()->new ServiceException("仓库不存在"));}
    private String defaultCode(){String value=config.selectConfigByKey("fashion.catalog.defaultWarehouse");return value==null||value.isBlank()?"MAIN":value;}
    private void checkDisable(String code,String status){if("inactive".equals(status)&&defaultCode().equals(code))throw new ServiceException("请先将其他启用仓库设为默认，再停用当前默认仓库");}
    private static void validate(Write r){if(r==null||r.code()==null||!r.code().matches("[A-Z0-9][A-Z0-9_-]{0,63}")||r.name()==null||r.name().isBlank()||r.name().length()>100||!Set.of("active","inactive").contains(r.status()==null?"":r.status())||r.sort()!=null&&r.sort()<0||r.remark()!=null&&r.remark().length()>500)throw new ServiceException("仓库编码、名称、状态、顺序或备注无效");}
    private static void apply(SysDictData e,Write r){e.setDictLabel(r.name().trim());e.setStatus("active".equals(r.status())?"0":"1");e.setDictSort(r.sort()==null?0L:r.sort());e.setRemark(r.remark());}
}
