package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import java.util.Set;

/** V4顶层资源图复用原DAG、变量引用与路径预算；原多终End分支保持可执行。 */
public final class DynamicModelGraphPolicy {
    private static final Set<String> ALLOWED=Set.of("node-start","node-end","if-else","text-joiner","message","node-variable",
        "spark-llm","extractor-parameter","decision-making","question-answer","database","plugin");
    private DynamicModelGraphPolicy() {}
    public static PureIterationPolicy.Budget validate(JSONObject execution) {
        return PureIterationPolicy.validateTopLevel(execution,ALLOWED,true);
    }
}
