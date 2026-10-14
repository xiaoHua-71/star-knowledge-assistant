package com.xiaohua.star.agent;

/**
 * 一次工具执行的结果（{@link AgentToolRegistry#execute} 的返回）。
 *
 * @param text      给模型读的结果文本（也是 {@code tool_result} 事件的 {@code result} 来源）
 * @param isError   是否执行出错（出错也算一次成功的「执行」，只是结果里说明了错误，模型据此可以纠正）
 * @param elapsedMs 耗时（毫秒），用于事件里的 {@code elapsedMs}
 */
public record ToolRunResult(String text, boolean isError, long elapsedMs) {
}
