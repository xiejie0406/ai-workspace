package com.ruoyi.aipicture.tool;

import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.aipicture.domain.AiPictureException;

/** 使用当前若依权限集合，不引入第二个权限仓库。 */
public final class ToolPermissionGuard {
    private ToolPermissionGuard() { }
    public static void check(String tool) {
        String permission=switch(tool) {case "generate_image"->"aipicture:image:generate";case "batch_process"->"aipicture:batch:execute";case "export"->"aipicture:export:download";default->"aipicture:tool:execute";};
        var permissions=SecurityUtils.getLoginUser().getPermissions();
        if(permissions==null||!permissions.contains("*:*:*")&&!permissions.contains(permission)) throw new AiPictureException(403,"TOOL_PERMISSION_DENIED","没有此图片工具的执行权限");
    }
}
