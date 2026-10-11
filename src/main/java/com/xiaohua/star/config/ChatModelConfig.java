package com.xiaohua.star.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话模型装配（DeepSeek，OpenAI 兼容协议）。
 *
 * <p>和 {@link AiModelConfig} 里那两个本地模型（embedding / rerank）分开放：那两个是
 * 进程内 ONNX、模型文件打进 jar、零调用成本；这个是远端 API、按量计费，两者配置来源与
 * 生命周期都不同，混在一个类里只会让读者猜「这个字段是给谁的」。
 *
 * <p><b>为什么条件装配（没配 key 就不建 Bean）</b>：
 * <ol>
 *   <li>缺 key 时 {@code OpenAiChatModel} 构造会抛异常，会把整个应用 —— 包括
 *       {@code MainApplicationTests} 这类加载全上下文的测试 —— 一起带崩；</li>
 *   <li>「没配 key 就没有对话能力」比「启动即失败」更符合本地开发的预期：想测对话时再配 key。</li>
 * </ol>
 * 代价：M4 起若某处注入了 {@code ChatModel} 却没配 key，会看到
 * {@code NoSuchBeanDefinitionException} —— 提示明确，不是静默出错。
 */
@Slf4j
@Configuration
public class ChatModelConfig {

    /** 配了非空 api-key 才装配；占位符由 Environment 解析。 */
    private static final String HAS_API_KEY = "'${ai.chat.api-key:}'.length() > 0";

    @Bean
    @ConditionalOnExpression(HAS_API_KEY)
    public ChatModel chatModel(ChatModelProperties props) {
        log.info("装配对话模型: baseUrl={}, model={}", props.getBaseUrl(), props.getModelName());
        return OpenAiChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(props.getModelName())
                .temperature(props.getTemperature())
                .timeout(props.getTimeout())
                .logRequests(props.isLogRequests())
                .logResponses(props.isLogResponses())
                .build();
    }

    @Bean
    @ConditionalOnExpression(HAS_API_KEY)
    public StreamingChatModel streamingChatModel(ChatModelProperties props) {
        log.info("装配流式对话模型: baseUrl={}, model={}", props.getBaseUrl(), props.getModelName());
        return OpenAiStreamingChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(props.getModelName())
                .temperature(props.getTemperature())
                .timeout(props.getTimeout())
                .logRequests(props.isLogRequests())
                .logResponses(props.isLogResponses())
                .build();
    }
}
