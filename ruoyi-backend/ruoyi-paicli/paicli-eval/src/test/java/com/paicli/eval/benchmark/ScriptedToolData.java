package com.paicli.eval.benchmark;

import com.paicli.llm.LlmClient;
import java.util.List;
import java.util.regex.Pattern;

/** 脚本模型读取工具数据；生产模型的不可信数据边界仍完整保留。 */
final class ScriptedToolData {
    private static final Pattern ENVELOPE = Pattern.compile(
            "\\A<tool_result tool=\"[A-Za-z0-9_.:-]+\" trust=\"untrusted-data\">\\n(.*)\\n</tool_result>\\z",
            Pattern.DOTALL);
    private ScriptedToolData() { }
    static String last(List<LlmClient.Message> messages) {
        var message = messages.stream().filter(m -> "tool".equals(m.role()))
                .reduce((a, b) -> b).orElseThrow();
        var match = ENVELOPE.matcher(message.content());
        if (!match.matches()) throw new AssertionError("脚本模型未收到完整工具数据边界");
        return match.group(1);
    }
}
