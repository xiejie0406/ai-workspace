package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.NotificationService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/legacy/notifications")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyNotificationController {
    private final NotificationService notifications;
    public LegacyNotificationController(NotificationService notifications){this.notifications=notifications;}
    @GetMapping("/list")
    public LegacyResult<JSONObject> list(@RequestParam(required=false) String type,@RequestParam(defaultValue="false") boolean unreadOnly,@RequestParam(defaultValue="1") int pageIndex,@RequestParam(defaultValue="20") int pageSize){return LegacyResult.success(notifications.list(type,unreadOnly,pageIndex,pageSize));}
    @GetMapping("/unread-count") public LegacyResult<Long> unread(){return LegacyResult.success(notifications.unread());}
    public record MarkRead(List<Long> notificationIds,Boolean markAll){}
    @PostMapping("/mark-read") @Log(title="PaiFlow通知已读",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Boolean> read(@RequestBody MarkRead body){if(body==null)throw new com.ruoyi.common.exception.ServiceException("通知已读请求不能为空",400);return LegacyResult.success(notifications.read(body.notificationIds(),body.markAll()));}
    @DeleteMapping("/{id}") @Log(title="PaiFlow通知删除",businessType=BusinessType.DELETE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Boolean> remove(@PathVariable long id){return LegacyResult.success(notifications.remove(id));}
}
