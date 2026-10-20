package com.xiaohua.star.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 可观测性配置（application.yml 的 {@code ai.observability.*}）。见 knowledge/50。
 *
 * <p><b>价格默认 0 = 未配置</b>。此时 {@code ai_call_log.costYuan} 写 {@code NULL}，而不是 0——
 * 「不知道成本」和「成本为零」必须能区分。填了价格才计算。
 *
 * <p>⚠️ 价格会随厂商调整，且不同模型/缓存命中/思考 token 的计费方式不同。这里的单价是**未标定**的
 * 粗粒度估算，只用于趋势观察，不能当账单。
 */
@Component
@ConfigurationProperties(prefix = "ai.observability")
@Data
public class ObservabilityProperties {

    /** 总开关。关掉后不写日志（接口仍可用，只是没有新数据）。 */
    private boolean enabled = true;

    /** 输入 token 单价（元 / 千 token）。0 = 未配置。 */
    private BigDecimal inputPricePer1kYuan = BigDecimal.ZERO;

    /** 输出 token 单价（元 / 千 token）。0 = 未配置。 */
    private BigDecimal outputPricePer1kYuan = BigDecimal.ZERO;

    /** requestJson / responseJson / 工具入参出参落库前的最大字符数。 */
    private int maxSnapshotChars = 8000;
}
