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

    /** 是否需人工确认。M7 尚未消费（属 M10），先随 {@link ToolMeta} 一起声明，供 frontend/60 清单使用。 */
    boolean requireApproval() default false;
}
