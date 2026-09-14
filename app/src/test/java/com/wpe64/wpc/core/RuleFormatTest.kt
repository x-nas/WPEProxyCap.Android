package com.wpe64.wpc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleFormatTest {

    private fun r(type: RuleType?, arg: String, action: RuleAction? = RuleAction.PROXY) =
        RuleInfo(type, type?.ordinal?.toString() ?: "99", arg, action)

    @Test
    fun plainRules() {
        assertEquals("DOMAIN-SUFFIX,qq.com,DIRECT", RuleFormat.format(r(RuleType.DOMAIN_SUFFIX, "qq.com", RuleAction.DIRECT)).line)
        assertEquals("DST-PORT,443,PROXY", RuleFormat.format(r(RuleType.DST_PORT, " 443 ")).line)
        assertEquals("MATCH,REJECT", RuleFormat.format(r(RuleType.MATCH, "ignored", RuleAction.REJECT)).line)
    }

    @Test
    fun noResolveMovesAfterTheAction() {
        assertEquals("IP-CIDR,1.2.3.0/24,PROXY,no-resolve", RuleFormat.format(r(RuleType.IP_CIDR, "1.2.3.0/24,no-resolve")).line)
        assertEquals("GEOIP,CN,DIRECT,no-resolve", RuleFormat.format(r(RuleType.GEOIP, "CN, NO-RESOLVE", RuleAction.DIRECT)).line)
        // 只有地址类规则认 no-resolve
        assertNull(RuleFormat.format(r(RuleType.DOMAIN, "a.com,no-resolve")).line)
    }

    @Test
    fun logicalRulesKeepTheirCommas() {
        val line = RuleFormat.format(r(RuleType.AND, "((DOMAIN,baidu.com),(NETWORK,UDP))", RuleAction.DIRECT)).line
        assertEquals("AND,((DOMAIN,baidu.com),(NETWORK,UDP)),DIRECT", line)
    }

    @Test
    fun skippedRulesCarryAReason() {
        val cases = listOf(
            r(RuleType.RULE_SET, "abc"),
            r(RuleType.SUB_RULE, "x"),
            r(RuleType.UI_EX, "x"),
            r(RuleType.COMMAND, "x"),
            r(RuleType.DEVICE_NAME, "x"),
            r(RuleType.PROCESS_NAME, "game.exe"),
            r(RuleType.PROCESS_PATH, "C:\\game.exe"),
            r(RuleType.DOMAIN, "   "),
            r(RuleType.DST_PORT, "80,443"),
            r(null, "x"),
            r(RuleType.DOMAIN, "a.com", action = null),
        )
        for (c in cases) {
            val res = RuleFormat.format(c)
            assertNull("应当跳过：$c", res.line)
            assert(res.reason.isNotEmpty()) { "跳过要给原因：$c" }
        }
    }

    @Test
    fun formatRulesFallsBackToDirect() {
        val all = ConfigBuilder.formatRules(listOf(r(RuleType.RULE_SET, "a"), r(RuleType.PROCESS_NAME, "b")))
        assertEquals(listOf("MATCH,DIRECT"), all.lines)
        assertEquals(2, all.skipped.size)

        assertEquals(listOf("MATCH,DIRECT"), ConfigBuilder.formatRules(emptyList()).lines)
    }
}
