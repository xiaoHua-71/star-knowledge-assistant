package com.xiaohua.star.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 工具清单项（{@code GET /tools/list} 的返回值）。契约见 frontend/60。
 *
 * <p>用途有二：① 前端渲染「助手能做什么」的能力清单；② 把 {@code tool_call} 事件里的英文
 * {@code toolName} 映射成 {@code displayName} 再展示。所以 {@code displayName} 必须有值。
 */
@Data
public class ToolInfoVO implements Serializable {

    /** 工具英文名（模型看到的标识符，如 {@code searchKnowledge}） */
    private String name;

    /** 中文名，给用户看（如「检索知识库」）。前端若拿不到要自行兜底，不要直接显示 name */
    private String displayName;

    /** 给模型看的描述 */
    private String description;

    /** 是否有副作用。{@code false} = 只读 */
    private Boolean sideEffect;

    /** 是否需人工确认（副作用工具通常为 true，但「幂等的副作用工具」可以 false） */
    private Boolean requireApproval;

    /** 参数清单（从工具 schema 派生）。无参工具为空列表 */
    private List<Param> parameters;

    @Data
    public static class Param implements Serializable {

        private String name;

        /** 参数类型的 JSON schema 名（string / integer / number / boolean / object…） */
        private String type;

        private Boolean required;

        private String description;

        private static final long serialVersionUID = 1L;
    }

    private static final long serialVersionUID = 1L;
}
