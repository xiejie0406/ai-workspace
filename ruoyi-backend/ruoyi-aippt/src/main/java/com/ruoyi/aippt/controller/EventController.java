package com.ruoyi.aippt.controller;
import com.ruoyi.aippt.application.*;
import com.ruoyi.aippt.configuration.AipptEnabled;
import com.ruoyi.common.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
@RestController @RequestMapping("/aippt") @AipptEnabled public class EventController  {
    private final EventService events;
    private final AipptService service;
    private final com.ruoyi.framework.web.service.TokenService tokens;
    public EventController(EventService e,AipptService s,com.ruoyi.framework.web.service.TokenService t)  {
        events=e;
        service=s;
        tokens=t;
    }
    private long cursor(long after,String last)  {
        if(last==null||last.isBlank())return after;
        try  {
            return Math.max(after,Long.parseLong(last));
        }
        catch(NumberFormatException e)  {
            throw com.ruoyi.aippt.domain.AipptException.invalid("事件游标无效");
        }
    }
    @GetMapping(value="/tasks/{id}/events",produces="text/event-stream") @PreAuthorize("@ss.hasAnyPermi('"+AipptService.TASK_READ_PERMISSIONS+"')") public SseEmitter task(@PathVariable String id,@RequestParam(defaultValue="0")long afterSeq,@RequestHeader(value="Last-Event-ID",required=false)String last,HttpServletRequest request)  {
        long owner=SecurityUtils.getUserId();
        String p=service.task(id,owner,SecurityUtils::hasPermi).getString("project_id");
        return events.subscribe(p,id,false,owner,cursor(afterSeq,last),()->taskSessionValid(request,id,owner));
    }
    /** 异步续查使用平台当前会话；不读取调度线程的 SecurityContext。 */
    private boolean taskSessionValid(HttpServletRequest request,String id,long owner) {
        com.ruoyi.common.core.domain.model.LoginUser current=tokens.getLoginUser(request);
        if(current==null||!java.util.Objects.equals(current.getUserId(),owner))return false;
        service.task(id,owner,permission->current.getPermissions()!=null&&SecurityUtils.hasPermi(current.getPermissions(),permission));
        return true;
    }
    private boolean projectSessionValid(HttpServletRequest request,long owner,String permission) {
        com.ruoyi.common.core.domain.model.LoginUser current=tokens.getLoginUser(request);
        return current!=null&&java.util.Objects.equals(current.getUserId(),owner)&&current.getPermissions()!=null
                &&SecurityUtils.hasPermi(current.getPermissions(),permission);
    }
    @GetMapping(value="/projects/{p}/deck/events",produces="text/event-stream") @PreAuthorize("@ss.hasPermi('aippt:deck:query')") public SseEmitter deck(@PathVariable String p,@RequestParam(defaultValue="0")long afterSeq,@RequestHeader(value="Last-Event-ID",required=false)String last,HttpServletRequest request)  {
        long owner=SecurityUtils.getUserId();
        return events.subscribe(p,null,true,owner,cursor(afterSeq,last),()->projectSessionValid(request,owner,"aippt:deck:query"));
    }
    @GetMapping(value="/projects/{p}/outline/events",produces="text/event-stream") @PreAuthorize("@ss.hasPermi('aippt:outline:query')") public SseEmitter outline(@PathVariable String p,@RequestParam(defaultValue="0")long afterSeq,@RequestHeader(value="Last-Event-ID",required=false)String last,HttpServletRequest request)  {
        long owner=SecurityUtils.getUserId();
        return events.subscribe(p,null,false,owner,cursor(afterSeq,last),()->projectSessionValid(request,owner,"aippt:outline:query"));
    }
}
