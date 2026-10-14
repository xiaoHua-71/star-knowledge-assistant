package com.xiaohua.star.agent.tool;

import com.xiaohua.star.agent.AgentToolPack;
import com.xiaohua.star.agent.RunContext;
import com.xiaohua.star.agent.ToolMeta;
import com.xiaohua.star.ai.RetrievalContextBuilder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 只读工具：检索知识库（见 knowledge/30）。
 *
 * <p><b>这是 RAG 的入口</b>。M7 起，检索从「每次对话自动执行」改为「模型按需调用本工具」
 * （决策记录见 knowledge/30 ③）——所以本工具是知识库内容进入回答的唯一通道。
 *
 * <p>复用 {@link RetrievalContextBuilder}（逐库检索 + round-robin 合并 + 免责声明），查询范围
 * 由 {@link RunContext#kbIds()} 决定，经 {@code @ToolMemoryId} 注入（不进模型可见参数）。
 */
@Component
public class KnowledgeTool implements AgentToolPack {

    @Resource
    private RetrievalContextBuilder retrievalContextBuilder;

    @Tool("在用户当前会话关联的知识库中检索相关内容。当问题可能与用户上传的资料/知识库有关时，"
            + "调用它来获取可引用的依据，再据此回答。")
    @ToolMeta(displayName = "检索知识库", sideEffect = false)
    public String searchKnowledge(
            @P(name = "query", description = "检索关键词或问题，用自然语言描述要查找的内容") String query,
            @ToolMemoryId RunContext ctx) {
        if (ctx.kbIds().isEmpty()) {
            return "当前会话没有关联任何知识库，无法检索。请直接基于已有知识回答，并提示用户可先关联知识库。";
        }
        String context = retrievalContextBuilder.build(ctx.kbIds(), query, ctx.userId());
        if (context == null || context.isBlank()) {
            return "未在知识库中检索到与「" + query + "」相关的内容。";
        }
        return context;
    }
}
