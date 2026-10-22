package com.xiaohua.star.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * token 成本估算（M12）：价格未配置必须返回 null，而不是 0。见 knowledge/50。
 */
class CostCalculatorTest {

    @Test
    void 价格未配置时返回null() {
        assertNull(CostCalculator.calculate(1000, 500, BigDecimal.ZERO, BigDecimal.ZERO));
        assertNull(CostCalculator.calculate(1000, 500, null, null));
    }

    @Test
    void 按千token单价计算并保留6位小数() {
        BigDecimal cost = CostCalculator.calculate(1000, 500,
                new BigDecimal("0.001"), new BigDecimal("0.002"));
        // 1000/1000*0.001 + 500/1000*0.002 = 0.001 + 0.001 = 0.002
        assertEquals(0, new BigDecimal("0.002000").compareTo(cost));
    }

    @Test
    void 缺失token按0处理() {
        BigDecimal cost = CostCalculator.calculate(null, null,
                new BigDecimal("1"), new BigDecimal("1"));
        assertEquals(0, BigDecimal.ZERO.compareTo(cost));
    }

    @Test
    void 展示时去掉尾零() {
        assertEquals("0.002", CostCalculator.display(new BigDecimal("0.002000")));
        assertEquals("1", CostCalculator.display(new BigDecimal("1.000000")));
        assertNull(CostCalculator.display(null));
    }
}
