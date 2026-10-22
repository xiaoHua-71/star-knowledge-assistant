package com.xiaohua.star.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 主循环的参数（application.yml 的 ai.agent.*）。见 knowledge/30。
 */
@Component
@ConfigurationProperties(prefix = "ai.agent")
@Data
public class AgentProperties {

    /**
     * 工具调用循环的最大轮次护栏（一轮 = 一次「模型响应 + 可能的一批工具执行」）。
     *
     * <p>没有它，一个不断要求调工具的模型会让循环无限跑下去（成本与延迟失控）。LC4j 的
     * {@code maxToolCallingRoundTrips} 是同一个思路（见 knowledge/12），我们在自研循环里复刻。
     * 注意「轮次」不是「工具个数」：一轮里模型可以要求调多个工具。
     */
    private int maxRounds = 8;

    /**
     * 单个工具结果进入 prompt / 事件前允许的最大字符数，超出截断。
     *
     * <p>工具结果会进 prompt（占 token 预算），也可能很长（如检索上下文）。截断既控成本，
     * 也避免一个工具的返回把上下文顶穿。事件里用 {@code truncated=true} 告诉前端结果被截过。
     */
    private int maxToolResultChars = 4000;

    /** SSE {@code ping} 心跳间隔（ms）；<=0 关闭。 */
    private long ssePingIntervalMs = 15_000L;

    /** {@code SUSPENDED} 运行超过该时长（ms）后封存为 {@code CANCELLED}；<=0 不封存。 */
    private long suspendRetentionMs = 24L * 60 * 60 * 1000;
}
