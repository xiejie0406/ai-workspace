package com.ruoyi.paiflow.domain;

import java.util.List;
import java.util.Set;

/** 原Python注册的完整节点目录。能力声明不表示Worker已经启动或可用。 */
public final class NodeCatalog {
    public static final Set<String> ENGINES = Set.of("java", "python");
    public record Entry(String type, String label, boolean internal, Set<String> engines) {}
    private static final Set<String> BOTH = Set.of("java", "python");
    private static final Set<String> PYTHON = Set.of("python");
    private static final List<Entry> ENTRIES = List.of(
        new Entry("node-start", "开始", false, BOTH),
        new Entry("node-end", "结束", false, BOTH),
        new Entry("spark-llm", "大模型", false, BOTH),
        new Entry("plugin", "工具", false, BOTH),
        new Entry("knowledge-base", "知识库", false, PYTHON),
        new Entry("knowledge-pro-base", "专业知识库", false, PYTHON),
        new Entry("extractor-parameter", "参数提取", false, PYTHON),
        new Entry("decision-making", "意图决策", false, PYTHON),
        new Entry("if-else", "条件", false, PYTHON),
        new Entry("message", "消息", false, PYTHON),
        new Entry("iteration", "迭代", false, PYTHON),
        new Entry("iteration-node-start", "迭代开始", true, PYTHON),
        new Entry("iteration-node-end", "迭代结束", true, PYTHON),
        new Entry("text-joiner", "文本处理", false, PYTHON),
        new Entry("node-variable", "全局变量", false, PYTHON),
        new Entry("flow", "子流程", false, PYTHON),
        new Entry("agent", "智能体", false, PYTHON),
        new Entry("question-answer", "问答", false, PYTHON),
        new Entry("database", "数据库", false, PYTHON),
        new Entry("rpa", "RPA", false, PYTHON),
        new Entry("ifly-code", "代码", false, PYTHON)
    );
    private NodeCatalog() {}
    public static List<Entry> entries() { return ENTRIES; }
    public static Entry find(String type) {
        return ENTRIES.stream().filter(e -> e.type().equals(type)).findFirst().orElse(null);
    }
    public static String typeOf(String id) {
        if (id == null) return "";
        int split = id.indexOf("::");
        return split < 0 ? "" : id.substring(0, split);
    }
}
