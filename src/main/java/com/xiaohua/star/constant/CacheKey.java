package com.xiaohua.star.constant;

/**
 * 缓存 key 常量
 */
public enum CacheKey {

    EMAIL_CODE("email:code:"),

    /**
     * Agent 运行的中断标志（见 knowledge/12）。key = {@code agent:interrupt:{runId}}，值 = {@code InterruptTypeEnum} 名。
     * <p><b>不设 TTL、必须显式删除</b>：丢了最坏是多跑一轮，但若过期时机不对会「用户点了停止、标志却没了」。
     */
    AGENT_INTERRUPT("agent:interrupt:");

    private final String prefix;

    CacheKey(String prefix) {
        this.prefix = prefix;
    }

    public String key(String key) {
        return prefix + key;
    }
}
