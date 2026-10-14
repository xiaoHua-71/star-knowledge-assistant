package com.xiaohua.star.agent;

import java.util.List;

/**
 * 一次 Agent 运行的上下文，经 {@code @ToolMemoryId} 注入工具方法（见 knowledge/30）。
 *
 * <p>LC4j 的 {@code @ToolMemoryId} 参数不进工具 schema、由框架用 {@code InvocationContext.chatMemoryId()}
 * 注入——把「这次运行属于谁、要查哪些库」这类<b>运行态信息</b>带给工具，而不污染模型可见的参数列表。
 *
 * <p>M7 只承载工具需要的归属信息；运行/步骤状态是 M8 的事，这里刻意不含。
 */
public record RunContext(Long userId, Long conversationId, List<Long> kbIds) {

    public RunContext {
        kbIds = kbIds == null ? List.of() : List.copyOf(kbIds);
    }
}
