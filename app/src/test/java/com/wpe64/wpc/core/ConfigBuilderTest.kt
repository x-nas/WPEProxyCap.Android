package com.wpe64.wpc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigBuilderTest {

    /** 单元测试的工作目录是 app/，模板就是运行时 App 从 assets 读的那一份。 */
    private val template = File("src/main/assets/base-android.yaml").readText()

    private val input = ConfigBuilder.Input("10.0.0.1", 1080, "player_001", "wpc1.abc'#def")

    @Test
    fun yamlQuoteEscapesSingleQuotes() {
        assertEquals("'a''b'", ConfigBuilder.yamlQuote("a'b"))
        assertEquals("'#not-a-comment'", ConfigBuilder.yamlQuote("#not-a-comment"))
        assertEquals("''", ConfigBuilder.yamlQuote(""))
    }

    @Test
    fun buildFillsPlaceholdersAndAppendsRules() {
        val yaml = ConfigBuilder.build(template, input, listOf("DOMAIN-SUFFIX,qq.com,DIRECT", "MATCH,PROXY"))

        assertFalse("占位符都要被填掉", Regex("\\{(server|port|username|password)\\}").containsMatchIn(yaml))
        assertTrue(yaml.contains("server: '10.0.0.1'"))
        assertTrue(yaml.contains("port: 1080"))
        assertTrue(yaml.contains("password: 'wpc1.abc''#def'"))
        assertTrue(yaml.trimEnd().endsWith("rules:\n  - DOMAIN-SUFFIX,qq.com,DIRECT\n  - MATCH,PROXY"))
    }

    @Test
    fun templateMatchesTheVpnServiceTunnel() {
        // 与 WpcVpnService 的 Builder 参数一致（改一边忘了改另一边，隧道就对不上）
        assertTrue(template.contains("mtu: 9000"))
        assertTrue(template.contains("172.19.0.1/30"))
        assertTrue(template.contains("fdfe:dcba:9876::1/126"))
        assertTrue("手机上不许起本地监听", !Regex("(?m)^(port|socks-port|mixed-port|external-controller):").containsMatchIn(template))
    }

    @Test
    fun kernelFilterDropsOnlyWhatTheKernelRejects() {
        val lines = listOf("DOMAIN-SUFFIX,qq.com,DIRECT", "BAD-RULE,x,PROXY", "MATCH,PROXY")
        // 假内核：配置里出现 BAD-RULE 就报错
        val fakeValidate: (String) -> String = { cfg -> if ("BAD-RULE" in cfg) "rules[1] [BAD-RULE,x,PROXY] error: unsupported rule type" else "" }

        val res = ConfigBuilder.filterWithKernel(template, input, lines, fakeValidate)
        assertEquals(listOf("DOMAIN-SUFFIX,qq.com,DIRECT", "MATCH,PROXY"), res.lines)
        assertEquals("BAD-RULE,x,PROXY", res.skipped.single().rule)
    }

    private fun rule(type: RuleType, arg: String, action: RuleAction) = RuleInfo(type, type.keyword, arg, action)

    @Test
    fun phoneSummaryCountsNoProxyWhenOnlyProcessRulesProxy() {
        // Windows 上这个节点只让 game.exe 走代理；手机上进程名规则被跳过，只剩全部直连
        val s = ConfigBuilder.phoneSummary(listOf(
            rule(RuleType.PROCESS_NAME, "game.exe", RuleAction.PROXY),
            rule(RuleType.MATCH, "", RuleAction.DIRECT),
        ))
        assertEquals(ConfigBuilder.PhoneSummary(total = 2, kept = 1, skipped = 1, proxy = 0), s)
    }

    @Test
    fun phoneSummaryCountsProxyRulesThatSurviveFiltering() {
        val s = ConfigBuilder.phoneSummary(listOf(
            rule(RuleType.DOMAIN_SUFFIX, "game.example.com", RuleAction.PROXY),
            rule(RuleType.IP_CIDR, "1.2.3.0/24,no-resolve", RuleAction.PROXY),
            rule(RuleType.PROCESS_PATH, "C:\\game.exe", RuleAction.PROXY),
            rule(RuleType.MATCH, "", RuleAction.DIRECT),
        ))
        assertEquals(ConfigBuilder.PhoneSummary(total = 4, kept = 3, skipped = 1, proxy = 2), s)
        assertEquals(ConfigBuilder.PhoneSummary(0, 0, 0, 0), ConfigBuilder.phoneSummary(emptyList()))
    }

    @Test
    fun proxyLineCountReadsTheActionNotTheArgument() {
        val lines = listOf(
            "IP-CIDR,1.2.3.0/24,PROXY,no-resolve",
            "AND,((DOMAIN,proxy.example.com),(NETWORK,UDP)),PROXY",
            "DOMAIN-KEYWORD,PROXY,DIRECT",
            "MATCH,DIRECT",
        )
        assertEquals(2, ConfigBuilder.proxyLineCount(lines))
        assertEquals(0, ConfigBuilder.proxyLineCount(listOf("MATCH,DIRECT")))
    }

    @Test
    fun kernelFilterIsANoOpWhenTheWholeConfigIsFine() {
        var calls = 0
        val res = ConfigBuilder.filterWithKernel(template, input, listOf("MATCH,DIRECT")) { calls++; "" }
        assertEquals(1, calls)
        assertTrue(res.skipped.isEmpty())
    }
}
