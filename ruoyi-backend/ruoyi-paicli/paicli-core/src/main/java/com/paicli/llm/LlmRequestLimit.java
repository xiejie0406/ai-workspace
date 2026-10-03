package com.paicli.llm;

/** 单请求上限由中心预算包装器绑定到实际 transport 线程。 */
public final class LlmRequestLimit implements AutoCloseable {
    private static final ThreadLocal<Integer> CURRENT = new ThreadLocal<>();
    private final Integer previous;
    private LlmRequestLimit(int tokens) { previous = CURRENT.get(); CURRENT.set(tokens); }
    public static LlmRequestLimit bind(int tokens) { if (tokens <= 0) throw new IllegalArgumentException("tokens"); return new LlmRequestLimit(tokens); }
    public static Integer current() { return CURRENT.get(); }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
