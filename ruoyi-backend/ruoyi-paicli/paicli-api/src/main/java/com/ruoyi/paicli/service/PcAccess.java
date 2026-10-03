package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.*;
import java.util.Objects;

public final class PcAccess {
    private final PcStore store;
    public PcAccess(PcStore store){this.store=store;}
    public PcRecord workspace(long id,long user,boolean lock){
        PcRecord w=store.require(PcTable.WORKSPACE,id,lock);if(!"ACTIVE".equals(w.getState()))throw PcException.deny("工作区已暂停");role(id,user);return w;
    }
    public String role(long workspace,long user){
        PcRecord m=store.mapper.findKey(PcTable.WORKSPACE_MEMBER,"workspace:"+workspace+":user:"+user,false);
        if(m==null||!"ACTIVE".equals(m.getState()))throw PcException.deny("没有该工作区成员权限");return PcJson.text(store.data(m),"role");
    }
    public void admin(long workspace,long user){workspace(workspace,user,false);if(!"ADMIN".equals(role(workspace,user)))throw PcException.deny("仅工作区管理员可维护配置和成员");}
    public void approve(long workspace,long user){String r=role(workspace,user);if(!r.equals("ADMIN")&&!r.equals("APPROVER"))throw PcException.deny("没有该工作区批准权限");}
    public PcRecord resource(PcTable table,long id,long user,boolean ownerOnly,boolean lock){
        PcRecord r=store.require(table,id,lock);if(r.getWorkspaceId()!=null)workspace(r.getWorkspaceId(),user,false);
        if((ownerOnly||r.getWorkspaceId()==null)&&!Objects.equals(r.getOwnerId(),user))throw PcException.deny("没有该对象权限");return r;
    }
}
