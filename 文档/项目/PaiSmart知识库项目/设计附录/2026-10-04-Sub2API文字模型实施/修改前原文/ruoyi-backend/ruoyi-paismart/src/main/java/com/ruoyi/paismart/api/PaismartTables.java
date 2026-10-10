package com.ruoyi.paismart.api;

import com.ruoyi.common.core.page.TableDataInfo;
import java.util.List;

/** 补齐共享若依分页响应的成功字段，不改变其类型或分页数据。 */
final class PaismartTables {
    private PaismartTables() {}
    static TableDataInfo page(){var result=new TableDataInfo();result.setCode(200);result.setMsg("查询成功");return result;}
    static TableDataInfo page(List<?> rows,long total){var result=page();result.setRows(rows);result.setTotal(total);return result;}
}
