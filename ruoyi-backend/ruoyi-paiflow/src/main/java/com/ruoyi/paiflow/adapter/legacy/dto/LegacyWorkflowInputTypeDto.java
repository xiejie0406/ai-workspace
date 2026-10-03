package com.ruoyi.paiflow.adapter.legacy.dto;

import com.alibaba.fastjson2.JSONObject;
import java.util.List;

/** 原Hub WorkflowInputTypeDto的12个nullable响应字段，仅用于兼容接口。 */
public final class LegacyWorkflowInputTypeDto {
    public String id;
    public String name;
    public String nameErrMsg;
    public JSONObject schema;
    public List<String> allowedFileType;
    public String fileType;
    public String description;
    public Boolean required;
    public Object refId;
    public Object deleteDisabled;
    public Object disabled;
    public String customParameterType;
}
