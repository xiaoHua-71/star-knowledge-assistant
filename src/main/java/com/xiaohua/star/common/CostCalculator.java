package com.xiaohua.star.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * token 成本估算（M12）。见 knowledge/50。
 *
 * <p><b>默认不猜价格</b>：两个单价都为 0 时返回 {@code null}，表示「成本未知」。
 * 金额一律用 {@link BigDecimal}，不用 double（浮点累加会漂移）。
 */
public final class CostCalculator {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final int SCALE = 6;

    private CostCalculator() {
    }

    /**
     * @return 成本（元，6 位小数）；单价未配置时返回 {@code null}
     */
    public static BigDecimal calculate(Integer promptTokens, Integer completionTokens,
                                       BigDecimal inputPricePer1kYuan,
                                       BigDecimal outputPricePer1kYuan) {
        BigDecimal inPrice = inputPricePer1kYuan == null ? BigDecimal.ZERO : inputPricePer1kYuan;
        BigDecimal outPrice = outputPricePer1kYuan == null ? BigDecimal.ZERO : outputPricePer1kYuan;
        if (inPrice.signum() == 0 && outPrice.signum() == 0) {
            return null;
        }
        BigDecimal in = BigDecimal.valueOf(promptTokens == null ? 0 : promptTokens)
                .multiply(inPrice).divide(THOUSAND, 10, RoundingMode.HALF_UP);
        BigDecimal out = BigDecimal.valueOf(completionTokens == null ? 0 : completionTokens)
                .multiply(outPrice).divide(THOUSAND, 10, RoundingMode.HALF_UP);
        return in.add(out).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 展示用：去掉无意义的尾零；{@code null} 原样返回。 */
    public static String display(BigDecimal cost) {
        return cost == null ? null : cost.stripTrailingZeros().toPlainString();
    }
}
