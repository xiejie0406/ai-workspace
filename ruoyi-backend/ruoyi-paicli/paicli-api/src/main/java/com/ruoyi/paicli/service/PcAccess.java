package com.ruoyi.paicli.service;
import com.ruoyi.paicli.domain.*;
import java.util.Objects;
/** 若依稳定用户标识是个人对象唯一授权依据；目录不是成员或角色容器。 */
public final class PcAccess {
 private final PcStore store;
 public PcAccess(PcStore store){this.store=store;}
 public PcRecord directory(long id,long user,boolean lock){
  PcRecord directory=store.require(PcTable.DIRECTORY_CONTEXT,id,lock);
  if(!Objects.equals(directory.getOwnerId(),user))throw PcException.deny("没有该个人目录权限");
  if(!"VERIFIED".equals(directory.getState()))throw new PcException(409,"PC_DIRECTORY_NOT_READY","个人目录尚未由执行宿主验证");
  return directory;
 }
 public String owner(long directory,long user){directory(directory,user,false);return "OWNER";}
 public void admin(long directory,long user){directory(directory,user,false);}
 public void approve(long directory,long user){directory(directory,user,false);}
 public PcRecord resource(PcTable table,long id,long user,boolean ownerOnly,boolean lock){
  PcRecord row=store.require(table,id,lock);
  boolean legacy=row.getWorkspaceId()!=null&&row.getDirectoryContextId()==null;
  boolean own=Objects.equals(row.getOwnerId(),user);
  if(legacy){
   PcRecord snapshot=store.mapper.findKey(PcTable.LEGACY_ACCESS_SNAPSHOT,"legacy:"+table.name()+":"+id+":user:"+user,false);
   if(!own&&(snapshot==null||!Objects.equals(snapshot.getOwnerId(),user)||!"FROZEN".equals(snapshot.getState())))throw PcException.deny("没有该历史档案权限");
   if(lock)throw new PcException(409,"PC_LEGACY_READ_ONLY","历史档案已冻结，请在个人对话中继续工作");
   return row;
  }
  if(!own)throw PcException.deny("没有该个人对象权限");
  if(row.getDirectoryContextId()!=null)directory(row.getDirectoryContextId(),user,false);
  return row;
 }
 @Deprecated public PcRecord workspace(long id,long user,boolean lock){throw new PcException(410,"PC_WORKSPACE_REMOVED","工作区和成员机制已退出，请使用个人上下文");}
 @Deprecated public String role(long id,long user){throw new PcException(410,"PC_WORKSPACE_REMOVED","工作区成员机制已退出");}
}
