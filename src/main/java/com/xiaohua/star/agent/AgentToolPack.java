package com.xiaohua.star.agent;

/**
 * 标记「持有 {@code @Tool} 方法的 Bean」。
 *
 * <p>作用只是让 {@link AgentToolRegistry} 能按类型注入 {@code List<AgentToolPack>}——Spring 无法
 * 按「带 {@code @Tool} 注解的类型」注入，所以用一个空标记接口把工具包收集起来。
 */
public interface AgentToolPack {
}
