package com.xiaohua.star.agent.tool;

import com.xiaohua.star.agent.RunContext;
import com.xiaohua.star.ai.RetrievalContextBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索知识库工具：有命中 / 无命中 / 无关联库三条路径（M7）。用假的 RetrievalContextBuilder，不启 Spring。
 */
class KnowledgeToolTest {

    @Test
    void 有命中时返回上下文块() {
        KnowledgeTool tool = toolReturning("【资料1】来源：《手册.pdf》\n年假每年 5 天");

        String result = tool.searchKnowledge("年假", new RunContext(1L, 2L, List.of(10L)));

        assertTrue(result.contains("年假每年 5 天"), result);
    }

    @Test
    void 无命中时回可读的未找到() {
        KnowledgeTool tool = toolReturning(null);

        String result = tool.searchKnowledge("年假", new RunContext(1L, 2L, List.of(10L)));

        assertTrue(result.contains("未在知识库中检索到"), result);
    }

    @Test
    void 没有关联知识库时不触碰检索() {
        KnowledgeTool tool = new KnowledgeTool();
        ReflectionTestUtils.setField(tool, "retrievalContextBuilder", new RetrievalContextBuilder() {
            @Override
            public String build(List<Long> kbIds, String query, Long userId) {
                throw new AssertionError("kbIds 为空时不应调用检索");
            }
        });

        String result = tool.searchKnowledge("年假", new RunContext(1L, 2L, List.of()));

        assertTrue(result.contains("没有关联任何知识库"), result);
    }

    private static KnowledgeTool toolReturning(String context) {
        KnowledgeTool tool = new KnowledgeTool();
        ReflectionTestUtils.setField(tool, "retrievalContextBuilder", new RetrievalContextBuilder() {
            @Override
            public String build(List<Long> kbIds, String query, Long userId) {
                return context;
            }
        });
        return tool;
    }
}
