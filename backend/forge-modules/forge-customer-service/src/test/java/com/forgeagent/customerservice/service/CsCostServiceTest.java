package com.forgeagent.customerservice.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** P2:成本金额换算口径(未配单价不写 0)。 */
@Tag("dev")
class CsCostServiceTest {

    @Test
    void amountIsNullWithoutPrice() {
        assertNull(CsCostService.costOf(1000, null));
        assertNull(CsCostService.costOf(1000, 0.0));
        assertNull(CsCostService.costOf(0, 0.02));
    }

    @Test
    void amountFollowsPricePerThousandTokens() {
        // 1000 token × 0.02 元/千 token = 0.02 元
        assertEquals(new BigDecimal("0.020000"), CsCostService.costOf(1000, 0.02));
        // 500 token 按比例折算
        assertEquals(new BigDecimal("0.010000"), CsCostService.costOf(500, 0.02));
    }

    @Test
    void amountIsRoundedToSixDecimals() {
        // 333 token × 0.003 元/千 token = 0.000999 元
        assertEquals(new BigDecimal("0.000999"), CsCostService.costOf(333, 0.003));
    }
}
