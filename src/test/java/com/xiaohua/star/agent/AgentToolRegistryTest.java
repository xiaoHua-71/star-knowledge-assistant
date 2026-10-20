package com.xiaohua.star.agent;

import com.xiaohua.star.agent.tool.CreateTicketTool;
import com.xiaohua.star.agent.tool.KnowledgeTool;
import com.xiaohua.star.agent.tool.SystemTimeTool;
import com.xiaohua.star.ai.RetrievalContextBuilder;
import com.xiaohua.star.model.vo.ToolInfoVO;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具注册中心：schema 生成、name→executor 映射、元信息、启动期校验、执行（M7）。纯逻辑，不启 Spring。
 */
class AgentToolRegistryTest {

    @Test
    void 生成两个工具的schema且RunContext不进参数表() {
        AgentToolRegistry registry = registry(new SystemTimeTool(), knowledgeTool(null));

        List<ToolSpecification> specs = registry.specifications();
        assertEquals(List.of("getCurrentTime", "searchKnowledge"),
                specs.stream().map(ToolSpecification::name).toList());

        ToolSpecification time = specs.get(0);
        assertNull(time.parameters(), "无参工具的 parameters schema 为 null（LC4j 省略空 schema）");

        ToolSpecification search = specs.get(1);
        assertEquals(List.of("query"), List.copyOf(search.parameters().properties().keySet()));
        assertEquals(List.of("query"), search.parameters().required());
        assertFalse(search.parameters().properties().containsKey("ctx"),
                "@ToolMemoryId 参数绝不能进模型可见的参数表");
    }

    @Test
    void toolInfos带中文名与元信息() {
        AgentToolRegistry registry = registry(new SystemTimeTool(), knowledgeTool(null));

        List<ToolInfoVO> infos = registry.toolInfos();
        assertEquals(2, infos.size());

        ToolInfoVO time = infos.get(0);
        assertEquals("getCurrentTime", time.getName());
        assertEquals("获取当前时间", time.getDisplayName());
        assertFalse(time.getSideEffect());
        assertFalse(time.getRequireApproval());
        assertTrue(time.getParameters().isEmpty());

        ToolInfoVO search = infos.get(1);
        assertEquals("检索知识库", search.getDisplayName());
        assertEquals("query", search.getParameters().get(0).getName());
        assertEquals("string", search.getParameters().get(0).getType());
        assertTrue(search.getParameters().get(0).getRequired());
    }

    @Test
    void 执行只读工具返回文本() {
        AgentToolRegistry registry = registry(new SystemTimeTool());

        ToolRunResult result = registry.execute(
                request("getCurrentTime", "{}"), new RunContext(1L, 2L, List.of()));

        assertFalse(result.isError());
        assertTrue(result.text().contains("当前时间"), "实际: " + result.text());
    }

    @Test
    void 未知工具名不抛异常而是回可读错误() {
        AgentToolRegistry registry = registry(new SystemTimeTool());

        ToolRunResult result = registry.execute(
                request("createTicket", "{}"), new RunContext(1L, 2L, List.of()));

        assertTrue(result.isError());
        assertTrue(result.text().contains("未知工具"), "实际: " + result.text());
        assertTrue(result.text().contains("getCurrentTime"), "应把可用工具列出来给模型");
    }

    @Test
    void 工具重名时启动即失败() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry(new DuplicateNamePack()));
        assertTrue(e.getMessage().contains("重名"), e.getMessage());
    }

    @Test
    void 缺ToolMeta时启动即失败() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry(new MissingMetaPack()));
        assertTrue(e.getMessage().contains("@ToolMeta"), e.getMessage());
    }

    @Test
    void ToolMemoryId参数类型不对时启动即失败() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry(new WrongContextTypePack()));
        assertTrue(e.getMessage().contains("RunContext"), e.getMessage());
    }

    @Test
    void 既不幂等又不确认的副作用工具启动即失败() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry(new UnsafeSideEffectPack()));
        assertTrue(e.getMessage().contains("idempotentBackend"), e.getMessage());
        assertTrue(e.getMessage().contains("requireApproval"), e.getMessage());
    }

    @Test
    void createTicket声明为需人工确认且非幂等后端() {
        AgentToolRegistry registry = registry(new CreateTicketTool());

        ToolInfoVO info = registry.toolInfos().get(0);
        assertEquals("createTicket", info.getName());
        assertEquals("创建工单", info.getDisplayName());
        assertTrue(info.getSideEffect());
        assertTrue(info.getRequireApproval());
        assertFalse(info.getIdempotentBackend());
        assertTrue(registry.requireApproval("createTicket"));
        assertTrue(registry.sideEffect("createTicket"));
    }

    // ---------------- 辅助 ----------------

    private static AgentToolRegistry registry(AgentToolPack... packs) {
        return new AgentToolRegistry(List.of(packs));
    }

    private static KnowledgeTool knowledgeTool(String fixedContext) {
        KnowledgeTool tool = new KnowledgeTool();
        ReflectionTestUtils.setField(tool, "retrievalContextBuilder", new RetrievalContextBuilder() {
            @Override
            public String build(List<Long> kbIds, String query, Long userId) {
                return fixedContext;
            }
        });
        return tool;
    }

    private static ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder().id("call_1").name(name).arguments(arguments).build();
    }

    static class DuplicateNamePack implements AgentToolPack {
        @Tool("第一个")
        @ToolMeta(displayName = "甲")
        public String dup() {
            return "1";
        }

        @Tool(name = "dup")
        @ToolMeta(displayName = "乙")
        public String dup2() {
            return "2";
        }
    }

    static class MissingMetaPack implements AgentToolPack {
        @Tool("没有元信息")
        public String noMeta() {
            return "1";
        }
    }

    static class WrongContextTypePack implements AgentToolPack {
        @Tool("上下文类型错")
        @ToolMeta(displayName = "丙")
        public String bad(@ToolMemoryId String ctx, @P(name = "x", description = "x") String x) {
            return ctx + x;
        }
    }

    /** 危险配置：有副作用、后端不幂等、又不需人工确认。 */
    static class UnsafeSideEffectPack implements AgentToolPack {
        @Tool("危险操作")
        @ToolMeta(displayName = "危险", sideEffect = true)
        public String unsafe() {
            return "x";
        }
    }
}
