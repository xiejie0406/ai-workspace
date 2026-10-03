package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 仅调用当前纯JSON边界；所有输入为人工无凭据样例。 */
public final class WorkflowYamlBoundaryIndependentProbe {
    public static void main(String[] args) throws Exception {
        JSONArray cases = JSON.parseArray(Files.readString(Path.of(args[0])));
        JSONArray observations = new JSONArray();
        for (Object raw : cases) {
            JSONObject test = (JSONObject) raw;
            Object input = test.get("input");
            String before = JSON.toJSONString(input, JSONWriter.Feature.WriteMapNullValue);
            JSONObject result = JSONObject.of("id", test.get("id"));
            try {
                WorkflowJsonBoundary.check(input, "exportCandidate", 16 * 1024 * 1024);
                result.put("accepted", true);
                result.put("code", null);
            } catch (ServiceException failure) {
                result.put("accepted", false);
                result.put("code", failure.getCode());
                result.put("message", failure.getMessage());
            }
            result.put("inputUnchanged", before.equals(JSON.toJSONString(input, JSONWriter.Feature.WriteMapNullValue)));
            observations.add(result);
        }
        System.out.println(observations.toJSONString(JSONWriter.Feature.WriteMapNullValue));
    }
}
