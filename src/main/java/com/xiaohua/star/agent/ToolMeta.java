package com.xiaohua.star.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具的业务元信息，加在 {@code @Tool} 方法上（见 knowledge/30）。
 *
 * <p>LC4j 的 {@code @Tool} 只描述「叫什么、给模型看什么」，没有副作用分级。本项目需要
 * {@code displayName}（给前端展示中文名）与 {@code sideEffect} / {@code requireApproval}
 * （副作用治理，见 knowledge/12），所以单独放一个注解，两者并存。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolMeta {

    /** 给用户看的中文名。绝不把英文工具名直接展示给用户（见 frontend/60）。 */
    String displayName();

    /** 是否只读。{@code false} = 只读；{@code true} = 会改变系统状态。 */
    boolean sideEffect() default false;

    /**
     * 后端接口是否支持传入幂等键（M10）。见 knowledge/12 ③「工具契约」。
     *
     * <p>为 true 表示「同一个幂等键重复调用不会产生第二次副作用」——中断续跑时可以直接重跑。
     * 为 false 的副作用工具必须配 {@link #requireApproval()}，否则<b>启动即失败</b>：
     * 一个既不幂等、又不需确认的副作用工具，一旦在恢复时重跑就会重复下单/发通知。
     */
    boolean idempotentBackend() default false;

    /** 是否需人工确认（M10 消费）。副作用工具的二选一兜底：不能幂等，就让人来点确认。 */
    boolean requireApproval() default false;

    /**
     * 工具来源（M12，仅写入 {@code ai_tool_log.source}，不参与调用）。
     * 例：{@code LOCAL}（本地计算）、{@code LOCAL_QUERY}（本地检索）、{@code INTERNAL}（内部业务系统）。
     */
    String source() default "LOCAL";
}
