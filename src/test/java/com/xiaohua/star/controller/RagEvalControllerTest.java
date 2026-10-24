package com.xiaohua.star.controller;

import com.xiaohua.star.exception.GlobalExceptionHandler;
import com.xiaohua.star.model.entity.User;
import com.xiaohua.star.model.vo.RagCorpusSummaryVO;
import com.xiaohua.star.model.vo.RagEvalStatusVO;
import com.xiaohua.star.service.UserService;
import com.xiaohua.star.service.eval.EvalCorpusService;
import com.xiaohua.star.service.eval.RagEvalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 评测 HTTP 接口单测：standalone MockMvc，不启 Spring、不连数据库。
 */
class RagEvalControllerTest {

    private UserService userService;
    private RagEvalService ragEvalService;
    private EvalCorpusService corpusService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        ragEvalService = mock(RagEvalService.class);
        corpusService = mock(EvalCorpusService.class);

        RagEvalController controller = new RagEvalController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "ragEvalService", ragEvalService);
        ReflectionTestUtils.setField(controller, "corpusService", corpusService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void login() {
        User user = new User();
        user.setId(1L);
        when(userService.getLoginUserOrNull(any())).thenReturn(user);
    }

    @Test
    void 未登录触发返回40100() throws Exception {
        when(userService.getLoginUserOrNull(any())).thenReturn(null);

        mockMvc.perform(post("/rag/eval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void 触发成功返回当前状态() throws Exception {
        login();
        when(ragEvalService.triggerEval(any(), isNull())).thenReturn(true);
        when(ragEvalService.getStatus()).thenReturn(RagEvalStatusVO.of("PREPARING", "已提交"));

        mockMvc.perform(post("/rag/eval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variants\":[\"all-on\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.state").value("PREPARING"));

        verify(ragEvalService).triggerEval(List.of("all-on"), null);
    }

    @Test
    void 已有评估在跑时返回50001() throws Exception {
        login();
        when(ragEvalService.triggerEval(any(), any())).thenReturn(false);

        mockMvc.perform(post("/rag/eval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50001));
    }

    @Test
    void 状态查询需要登录() throws Exception {
        when(userService.getLoginUserOrNull(any())).thenReturn(null);

        mockMvc.perform(get("/rag/eval/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void 还没有报告时返回40400() throws Exception {
        login();
        when(ragEvalService.getLatestReport()).thenReturn(null);

        mockMvc.perform(get("/rag/eval/report/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void 语料重建返回摘要() throws Exception {
        login();
        when(corpusService.ensureCorpus(true)).thenReturn(new RagCorpusSummaryVO(
                9L, "star_kb_9", "rag-eval-corpus", 94, 94, 1200, 94, 0, 0, true, List.of()));

        mockMvc.perform(post("/rag/eval/corpus/rebuild").param("full", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.corpusFiles").value(94))
                .andExpect(jsonPath("$.data.complete").value(true));
    }
}
