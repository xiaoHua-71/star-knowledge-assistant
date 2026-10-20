package com.xiaohua.star.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 副作用工具的幂等键（M10）。见 knowledge/12 ③「幂等键」。
 *
 * <pre>idempotencyKey = sha256(runId | stepNo | toolName | canonical(argumentsJson)) 取前 32 位</pre>
 *
 * <p><b>必须规范化参数</b>：模型输出的 JSON 键顺序不稳定，直接 hash 原始字符串会得到不稳定的键，
 * 唯一索引防线会静默失效（不报错，只是偶尔重复执行）。规范化规则：
 * <ul>
 *   <li>对象键按字典序排序（递归）</li>
 *   <li>忽略多余空白</li>
 *   <li>数值统一：{@code 5} / {@code 5.0} → {@code 5}，{@code 5.50} → {@code 5.5}</li>
 * </ul>
 */
public final class IdempotencyKeys {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IdempotencyKeys() {
    }

    public static String of(long runId, int stepNo, String toolName, String argumentsJson) {
        String raw = runId + "|" + stepNo + "|" + toolName + "|" + canonicalize(argumentsJson);
        return sha256Hex(raw).substring(0, 32);
    }

    /** 规范化 JSON；解析失败时退回「去首尾空白」的原文（宁可键不稳定，也不能让运行崩掉）。 */
    static String canonicalize(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return MAPPER.writeValueAsString(sort(node));
        } catch (Exception e) {
            return json.trim();
        }
    }

    /** JsonNode → 有序的 Map/List/标量。TreeMap 保证键序，List 保序。 */
    private static Object sort(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> out = new TreeMap<>();
            node.fields().forEachRemaining(e -> out.put(e.getKey(), sort(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>(node.size());
            node.forEach(child -> out.add(sort(child)));
            return out;
        }
        if (node.isNumber()) {
            return normalizeNumber(node);
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    private static String normalizeNumber(JsonNode node) {
        BigDecimal value = node.decimalValue().stripTrailingZeros();
        // 整数值不带小数点：5 而不是 5.0
        return value.scale() <= 0 ? value.toBigInteger().toString() : value.toPlainString();
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
