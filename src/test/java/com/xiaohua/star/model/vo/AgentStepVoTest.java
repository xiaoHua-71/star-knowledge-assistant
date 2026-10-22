package com.xiaohua.star.model.vo;

import com.xiaohua.star.model.entity.AgentStep;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行步骤 → AgentStepVO 映射（M13）。纯逻辑，不启 Spring。
 */
class AgentStepVoTest {

    @Test
    void 工具步解析参数与结果且标记重放() {
        AgentStep step = new AgentStep();
        step.setId(11L);
        step.setSeq(3);
        step.setType("TOOL_CALL");
        step.setStatus("SUCCEEDED");
        step.setToolName("createTicket");
        step.setArgumentsJson("{\"title\":\"登录失败\"}");
        step.setToolResultJson("{\"ticketId\":\"T-1\"}");
        step.setReplayed(1);
        step.setElapsedMs(12L);

        AgentStepVO vo = AgentStepVO.from(step);

        assertEquals(11L, vo.getId());
        assertEquals(3, vo.getSeq());
        assertEquals("createTicket", vo.getToolName());
        assertEquals(Map.of("title", "登录失败"), vo.getArguments());
        assertEquals(Map.of("ticketId", "T-1"), vo.getResult());
        assertTrue(vo.isReplayed());
        assertEquals(12, vo.getElapsedMs());
    }

    @Test
    void 非JSON结果包成text对象() {
        AgentStep step = new AgentStep();
        step.setType("TOOL_CALL");
        step.setStatus("FAILED");
        step.setToolName("createTicket");
        step.setToolResultJson("用户拒绝执行该操作，操作未执行。");
        step.setErrorMsg("用户拒绝");

        AgentStepVO vo = AgentStepVO.from(step);

        assertEquals(Map.of("text", "用户拒绝执行该操作，操作未执行。"), vo.getResult());
        assertEquals("用户拒绝", vo.getErrorMsg());
        assertFalse(vo.isReplayed());
    }

    @Test
    void LLM步结果取响应快照且无工具字段() {
        AgentStep step = new AgentStep();
        step.setType("LLM_CALL");
        step.setStatus("INTERRUPTED");
        step.setResponseJson("{\"text\":\"我查一下\",\"toolCalls\":[{\"id\":\"c1\",\"name\":\"searchKnowledge\",\"arguments\":\"{}\"}]}");

        AgentStepVO vo = AgentStepVO.from(step);

        assertNull(vo.getToolName());
        assertNull(vo.getArguments());
        assertTrue(vo.getResult() instanceof Map<?, ?>);
        assertEquals("我查一下", ((Map<?, ?>) vo.getResult()).get("text"));
    }

    @Test
    void 空步骤与空JSON都返回null() {
        assertNull(AgentStepVO.from(null));

        AgentStep step = new AgentStep();
        step.setType("TOOL_CALL");
        AgentStepVO vo = AgentStepVO.from(step);

        assertNull(vo.getArguments());
        assertNull(vo.getResult());
        assertFalse(vo.isReplayed());
    }
}
