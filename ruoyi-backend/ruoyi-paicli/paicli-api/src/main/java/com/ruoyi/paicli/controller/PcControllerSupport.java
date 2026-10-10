package com.ruoyi.paicli.controller;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.paicli.domain.*;
import com.ruoyi.paicli.service.PcStore;
import java.util.*;

abstract class PcControllerSupport extends BaseController {
    protected final PcStore store;
    protected PcControllerSupport(PcStore s){store=s;}
    protected long actor(){var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes a&&a.getRequest().getParameter("workspaceId")!=null)throw new PcException(410,"PC_CLIENT_UPGRADE_REQUIRED","工作区协议已退出，请升级客户端");return SecurityUtils.getUserId();}
    protected long version(String value){if(value==null)throw new PcException(428,"PC_VERSION_REQUIRED","需提供If-Match版本");try{String raw=value.replace("\"","");if(!raw.matches("[0-9]{1,18}"))throw new NumberFormatException();long v=Long.parseLong(raw);if(v<0)throw new NumberFormatException();return v;}catch(Exception e){throw PcException.invalid("If-Match须为当前数值版本");}}
    protected String like(String value){if(value==null||value.isBlank())return null;if(value.length()>2048)throw PcException.invalid("查询文字过长");return "%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    protected TableDataInfo page(List<PcRecord> rows){TableDataInfo result=getDataTable(rows);result.setRows(rows.stream().map(store::view).toList());return result;}
}
