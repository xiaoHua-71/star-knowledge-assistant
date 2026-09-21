package com.xiaohua.star.constant;

/**
 * 缓存 key 常量
 */
public enum CacheKey {

    EMAIL_CODE("email:code:");

    private final String prefix;

    CacheKey(String prefix) {
        this.prefix = prefix;
    }

    public String key(String key) {
        return prefix + key;
    }
}
