package com.xiaohua.star.agent;

import com.xiaohua.star.model.vo.ToolInfoVO;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心（见 knowledge/30）。
 *
 * <p>启动时扫描所有 {@link AgentToolPack}：为每个 {@code @Tool} 方法生成 {@link ToolSpecification}
 * （LC4j 反射生成 JSON schema）、建 {@code 工具名 → ToolExecutor} 与 {@code 工具名 → ToolMeta} 映射。
 * <b>配置有误就启动即失败</b>——重名、缺 {@link ToolMeta}、{@code @ToolMemoryId} 参数类型不对，
 * 都在这里当场抛出，而不是等第一次对话才发现（模块 11 的失败快原则）。
 *
 * <p>执行走 LC4j 的 {@link DefaultToolExecutor}：它负责 JSON 参数 → 方法入参的绑定与反射调用，
 * 这部分框架做得比自写好（见 knowledge/01 的取舍表）。我们用<b>方法级</b>构造器
 * （{@code DefaultToolExecutor(bean, method)}）而不是请求级（按方法名反射匹配），
 * 这样 {@code @Tool(name=...)} 与 Java 方法名可以不同，不受方法名约束。
 */
@Slf4j
@Component
public class AgentToolRegistry {

    /** 工具名 → 规范（发给模型的 schema） */
    private final Map<String, ToolSpecification> specByName = new LinkedHashMap<>();

    /** 工具名 → 执行器 */
    private final Map<String, ToolExecutor> executorByName = new HashMap<>();

    /** 工具名 → 业务元信息 */
    private final Map<String, ToolMeta> metaByName = new HashMap<>();

    public AgentToolRegistry(List<AgentToolPack> packs) {
        for (AgentToolPack pack : packs) {
            for (Method method : pack.getClass().getMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                register(pack, method);
            }
        }
        log.info("Agent 工具注册完成: {} 个 -> {}", specByName.size(), specByName.keySet());
    }

    private void register(AgentToolPack pack, Method method) {
        ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
        String name = spec.name();

        if (specByName.containsKey(name)) {
            throw new IllegalStateException(
                    "工具重名：'" + name + "'（" + method + "）；工具名必须全局唯一");
        }

        ToolMeta meta = method.getAnnotation(ToolMeta.class);
        if (meta == null) {
            throw new IllegalStateException(
                    "工具 '" + name + "'（" + method + "）缺少 @ToolMeta 注解"
                            + "——没有它就没有 displayName / 副作用分级，前端无法正确展示，副作用治理也无从谈起");
        }

        // @ToolMemoryId 参数会被注入 RunContext，类型不符会在运行期 ClassCastException；提前拦下
        for (Parameter p : method.getParameters()) {
            if (p.isAnnotationPresent(ToolMemoryId.class) && p.getType() != RunContext.class) {
                throw new IllegalStateException(
                        "工具 '" + name + "' 的 @ToolMemoryId 参数类型必须是 RunContext，实际是 " + p.getType());
            }
        }

        specByName.put(name, spec);
        executorByName.put(name, new DefaultToolExecutor(pack, method));
        metaByName.put(name, meta);
    }

    /** 发给模型的全部工具 schema，按名字排序（顺序稳定，便于测试与 diff）。 */
    public List<ToolSpecification> specifications() {
        List<ToolSpecification> list = new ArrayList<>(specByName.values());
        list.sort(Comparator.comparing(ToolSpecification::name));
        return list;
    }

    public boolean hasTools() {
        return !specByName.isEmpty();
    }

    public boolean sideEffect(String toolName) {
        ToolMeta meta = metaByName.get(toolName);
        return meta != null && meta.sideEffect();
    }

    /** 工具清单（frontend/60 的 {@code GET /tools/list}）。按名字排序。 */
    public List<ToolInfoVO> toolInfos() {
        List<ToolInfoVO> list = new ArrayList<>(specByName.size());
        for (ToolSpecification spec : specifications()) {
            ToolInfoVO vo = new ToolInfoVO();
            vo.setName(spec.name());
            vo.setDescription(spec.description());
            ToolMeta meta = metaByName.get(spec.name());
            vo.setDisplayName(meta.displayName());
            vo.setSideEffect(meta.sideEffect());
            vo.setRequireApproval(meta.requireApproval());
            vo.setParameters(paramsOf(spec.parameters()));
            list.add(vo);
        }
        return list;
    }

    /**
     * 执行一次工具调用。任何失败都转成 {@link ToolRunResult}（{@code isError=true}）返回，
     * <b>不向上抛</b>——错误作为工具结果回给模型，让它自行纠正或改口（见 knowledge/30 坑「工具错误回文本」）。
     */
    public ToolRunResult execute(ToolExecutionRequest request, RunContext ctx) {
        long start = System.nanoTime();
        ToolExecutor executor = executorByName.get(request.name());
        if (executor == null) {
            // 模型编造了不存在的工具名。给出可用清单，别让它继续瞎试，也别让整个运行崩掉
            return new ToolRunResult(
                    "未知工具「" + request.name() + "」。可用工具：" + String.join("、", executorByName.keySet())
                            + "。请只调用已提供的工具。",
                    true, elapsedMs(start));
        }
        try {
            ToolExecutionResult result = executor.executeWithContext(
                    request, InvocationContext.builder().chatMemoryId(ctx).build());
            return new ToolRunResult(result.resultText(), result.isError(), elapsedMs(start));
        } catch (Exception e) {
            log.warn("工具执行异常: name={}", request.name(), e);
            return new ToolRunResult(
                    "工具「" + request.name() + "」执行失败：" + e.getMessage(), true, elapsedMs(start));
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static List<ToolInfoVO.Param> paramsOf(JsonObjectSchema schema) {
        if (schema == null || schema.properties() == null || schema.properties().isEmpty()) {
            return List.of();
        }
        List<String> required = schema.required() == null ? List.of() : schema.required();
        List<ToolInfoVO.Param> params = new ArrayList<>(schema.properties().size());
        schema.properties().forEach((name, element) -> {
            ToolInfoVO.Param p = new ToolInfoVO.Param();
            p.setName(name);
            p.setType(typeName(element));
            p.setRequired(required.contains(name));
            p.setDescription(element.description());
            params.add(p);
        });
        return params;
    }

    /** JSON schema 元素 → 类型名。只认常见几种，其余归为 "object"。 */
    private static String typeName(JsonSchemaElement element) {
        if (element instanceof JsonStringSchema) {
            return "string";
        } else if (element instanceof JsonIntegerSchema) {
            return "integer";
        } else if (element instanceof JsonNumberSchema) {
            return "number";
        } else if (element instanceof JsonBooleanSchema) {
            return "boolean";
        } else if (element instanceof JsonEnumSchema) {
            return "string";
        } else if (element instanceof JsonArraySchema) {
            return "array";
        } else if (element instanceof JsonObjectSchema) {
            return "object";
        }
        return "object";
    }
}
