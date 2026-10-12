package com.xiaohua.star.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 远端对话模型（DeepSeek-V4.1-Flash，DeepSeek 官方 API）可用性验证 —— 对话与流式的地基。
 *
 * <p>不启 Spring：直接 new 模型，验的是「Key + 端点 + 模型名 三者配起来能不能真调通」，
 * 与框架装配无关（同 {@link LocalEmbeddingModelTest} 的思路）。
 *
 * <p><b>默认跳过</b>：需要系统环境变量 {@code DEEPSEEK_API_KEY} 才真正执行，没配就
 * {@code assumeTrue} 跳过 —— 这样干净检出、CI 都不会因为它变红。
 * 模型默认 {@code deepseek-flash}（DeepSeek 官方 ID），可用 {@code DEEPSEEK_CHAT_MODEL} 覆盖。
 *
 * <p>跑法：
 * <pre>
 *   export JAVA_HOME=/e/Java/jdk-21.0.5
 *   export DEEPSEEK_API_KEY=sk-xxxx
 *   mvn test -Dtest=DeepSeekChatModelTest -DfailIfNoSpecifiedTests=false
 * </pre>
 */
class DeepSeekChatModelTest {

    private static final String BASE_URL = env("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
    private static final String API_KEY = env("DEEPSEEK_API_KEY", "");
    // 模型名填 DeepSeek 官方 ID；仍可用 DEEPSEEK_CHAT_MODEL 覆盖
    private static final String MODEL = env("DEEPSEEK_CHAT_MODEL", "deepseek-flash");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = System.getProperty(name.toLowerCase().replace('_', '.'));
        }
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @Test
    void 非流式_能拿到非空回答并打印token用量() {
        assumeTrue(!API_KEY.isBlank(), "未设 DEEPSEEK_API_KEY，跳过");

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(BASE_URL)
                .apiKey(API_KEY)
                .modelName(MODEL)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(60))
                .build();

        ChatResponse response = model.chat(List.of(UserMessage.from("用一句话说明你是什么模型。")));

        String text = response.aiMessage().text();
        assertNotNull(text, "回答为 null");
        assertFalse(text.isBlank(), "回答为空");
        // 把真实 token 用量打出来 —— 记忆模块的 token 估算系数要靠这些真实值反推（见 knowledge/11）
        System.out.println("[DeepSeek] model=" + MODEL);
        System.out.println("[DeepSeek] reply=" + text);
        System.out.println("[DeepSeek] tokenUsage=" + response.metadata().tokenUsage());
    }

    @Test
    void 流式_能逐段收到且最终非空() throws Exception {
        assumeTrue(!API_KEY.isBlank(), "未设 DEEPSEEK_API_KEY，跳过");

        StreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl(BASE_URL)
                .apiKey(API_KEY)
                .modelName(MODEL)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(60))
                .build();

        StringBuilder received = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        List<ChatMessage> messages = List.of(UserMessage.from("从一数到五，用逗号分隔。"));
        model.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                received.append(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                done.countDown();
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                done.countDown();
            }
        });

        assertTrue(done.await(60, TimeUnit.SECONDS), "流式响应超时未结束");
        if (error.get() != null) {
            throw new AssertionError("流式调用出错: " + error.get().getMessage(), error.get());
        }
        System.out.println("[DeepSeek] stream reply=" + received);
        assertFalse(received.toString().isBlank(), "流式累计内容为空");
    }
}
