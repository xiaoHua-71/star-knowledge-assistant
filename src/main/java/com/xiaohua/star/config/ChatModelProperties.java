package com.xiaohua.star.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 对话模型配置（application.yml 的 ai.chat.*）。
 *
 * <p>用官方 {@code langchain4j-open-ai} 模块**直连 DeepSeek 官方 API**（OpenAI 兼容协议），
 * 模型为 DeepSeek-V4.1-Flash。所以「换模型 / 换端点」只改这里的 {@code baseUrl} / {@code modelName}，
 * 适配代码不动（为什么对话模型用远端、以及 provider 选型，见 knowledge/01）。
 *
 * <p><b>思考模式待验证：</b>DeepSeek V4 系列默认可能返回 {@code reasoning_content}（思维链）。
 * RAG 问答要不要它、带不带延迟/成本代价、能否关，等接上链路后实测再定。
 */
@Component
@ConfigurationProperties(prefix = "ai.chat")
@Data
public class ChatModelProperties {

    /**
     * API 基址。<b>DeepSeek 官方端点</b>（OpenAI 兼容）。换成任何 OpenAI 兼容服务只改这里。
     */
    private String baseUrl = "https://api.deepseek.com";

    /**
     * API Key。<b>默认空</b>，由系统环境变量 {@code DEEPSEEK_API_KEY}（DeepSeek 官方平台的 key）注入
     * （application.yml 里写的是 {@code ${DEEPSEEK_API_KEY:}}）。
     *
     * <p>空值时 {@link ChatModelConfig} 不装配对话模型 Bean —— 否则缺 key 会让整个应用启动失败。
     * 密钥刻意不写进版本库，这是对「keys 直接写在 yml」那条约定的例外（那里是 MySQL 密码这种本地库，这里是会花钱的外部凭据）。
     */
    private String apiKey = "";

    /**
     * provider 标识，仅用于可观测性（{@code ai_call_log.provider}），不参与调用。
     * 换到百炼时改成 {@code dashscope}，方便日志按 provider 聚合。
     */
    private String provider = "deepseek";

    /**
     * 模型名。DeepSeek <b>官方</b> API 的 ID 是 {@code deepseek-flash}（= DeepSeek-V4.1-Flash）。
     *
     * <p>⚠️ model ID 由服务提供方定义，<b>别和百炼的 ID 混用</b>：百炼上同一个模型叫
     * {@code deepseek-v4.1-flash}，官方叫 {@code deepseek-flash}——两者不是同一个串，
     * 换 provider 时这里和 {@code baseUrl} 要一起改（见 knowledge/01）。
     */
    private String modelName = "deepseek-flash";

    /**
     * 采样温度（未标定）。RAG 问答偏「忠实复述资料」，宜低温；先用 0.3 作起点。
     */
    private Double temperature = 0.3;

    /**
     * 单次请求超时。远端调用必须设 —— 不设的话底层 HTTP 客户端可能无限等。
     */
    private Duration timeout = Duration.ofSeconds(60);

    /** 是否打印请求体（排障用，默认关；开了会把 prompt 写进日志，注意脱敏）。 */
    private boolean logRequests = false;

    /** 是否打印响应体（排障用，默认关）。 */
    private boolean logResponses = false;
}
