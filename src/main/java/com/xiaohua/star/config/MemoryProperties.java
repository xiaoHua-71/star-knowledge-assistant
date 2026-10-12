package com.xiaohua.star.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话记忆的 token 预算（application.yml 的 ai.memory.*）。见 knowledge/11-对话记忆.md。
 *
 * <p><b>为什么要主动限一个上限</b>：当前模型（DeepSeek-V4.1-Flash）上下文高达 1M，理论上
 * 不需要裁。但历史越长 token 成本越高，所以这里<b>刻意把可用上下文压到 16K</b>（可配），
 * 让「记忆裁剪」真实发生、成本可控。
 *
 * <p><b>这里的数字全是「未标定」的起点</b>，不是最优值。特别是 {@code tokenPerChar}——中文和
 * 英文的系数明显不同，单系数是粗略近似；要标定得靠真实 token 用量反推（见 knowledge/11）。
 */
@Component
@ConfigurationProperties(prefix = "ai.memory")
@Data
public class MemoryProperties {

    /**
     * 本应用允许用到的上下文总量（token）。**不是**模型上限，是我们自设的成本闸。
     */
    private int contextWindow = 16000;

    /**
     * 给模型回答预留的 token（从预算里先扣掉，避免历史挤掉输出空间）。
     */
    private int reserveOutput = 2048;

    /**
     * 安全系数：预算只按上限的这个比例用，留余量吸收「按字符估算」的误差。默认 0.8。
     */
    private double safetyRatio = 0.8;

    /**
     * token 估算系数：{@code tokens ≈ 字符数 × 本值}。默认 1.5（偏保守，偏多估）。
     * <b>未标定</b>——中文/英文差异大，单系数只是近似。
     */
    private double tokenPerChar = 1.5;

    /**
     * 历史可用的 token 预算 = {@code (contextWindow - reserveOutput - 系统提示词token) × safetyRatio}。
     * 小于等于 0 时返回 0（此时只发系统提示词 + 最新一条消息）。
     */
    public int historyBudget(int systemPromptTokens) {
        int raw = contextWindow - reserveOutput - systemPromptTokens;
        if (raw <= 0) {
            return 0;
        }
        return (int) Math.floor(raw * safetyRatio);
    }
}
