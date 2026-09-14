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

    @Test
    fun kernelFilterIsANoOpWhenTheWholeConfigIsFine() {
        var calls = 0
        val res = ConfigBuilder.filterWithKernel(template, input, listOf("MATCH,DIRECT")) { calls++; "" }
        assertEquals(1, calls)
        assertTrue(res.skipped.isEmpty())
    }
}
