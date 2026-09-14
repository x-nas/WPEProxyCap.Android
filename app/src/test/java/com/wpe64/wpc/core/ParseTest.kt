package com.wpe64.wpc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseTest {

    /** WPE 的 /ProxyCap/GetServerList 原样应答：Json.NET 默认 PascalCase、枚举是数字（照 ClassObject/ServerInfo.cs、RuleInfo.cs）。 */
    private val wpeServers = """
        [
          {"IsEnable":true,"SID":"8d5c…","ServerName":"华东一区","ServerIP":"10.0.0.1","ServerPort":1080,
           "ForgotURL":"www.wpe64.com/forgot","RegisterURL":"","VerifyURL":"",
           "ServerRInfo":[
             {"IsEnable":true,"RID":"a","RType":1,"RArgument":"qq.com","RAction":2},
             {"IsEnable":true,"RID":"b","RType":15,"RArgument":"","RAction":0},
             {"IsEnable":true,"RID":"c","RType":99,"RArgument":"x","RAction":0}
           ]},
          {"IsEnable":true,"SID":"2","ServerName":"华东一区","ServerIP":"10.0.0.1","ServerPort":1080,"ServerRInfo":[]},
          {"IsEnable":true,"SID":"3","ServerName":"新加坡","ServerIP":"10.0.0.2","ServerPort":1081,"ServerRInfo":null}
        ]
    """.trimIndent()

    @Test
    fun serversParsePascalCaseAndIntEnums() {
        val list = Parse.servers(wpeServers)
        assertEquals(3, list.size)

        val s = list[0]
        assertEquals("华东一区", s.serverName)
        assertEquals("10.0.0.1", s.serverIP)
        assertEquals(1080, s.serverPort)
        assertEquals("www.wpe64.com/forgot", s.forgotURL)

        assertEquals(3, s.rules.size)
        assertEquals(RuleType.DOMAIN_SUFFIX, s.rules[0].type)
        assertEquals(RuleAction.DIRECT, s.rules[0].action)
        assertEquals(RuleType.MATCH, s.rules[1].type)
        assertNull("认不出的类型值留空，原值保留写日志用", s.rules[2].type)
        assertEquals("99", s.rules[2].typeRaw)

        assertTrue(list[2].rules.isEmpty())
    }

    @Test
    fun serverIdsAreStableAndDeduplicated() {
        val ids = Parse.servers(wpeServers).map { it.serverId }
        // 与 Windows 版同一条规则：「地址:端口/名称」，重名追加 #2
        assertEquals(listOf("10.0.0.1:1080/华东一区", "10.0.0.1:1080/华东一区#2", "10.0.0.2:1081/新加坡"), ids)
        // 再解析一次得到同样的 Id（刷新前后选中的节点不会指到别的上面）
        assertEquals(ids, Parse.servers(wpeServers).map { it.serverId })
    }

    @Test
    fun ruleEnumsAlsoAcceptNames() {
        assertEquals(RuleType.IP_CIDR, RuleType.parse("IP-CIDR"))
        assertEquals(RuleType.IP_CIDR, RuleType.parse("IP_CIDR"))
        assertEquals(RuleType.GEOIP, RuleType.parse("4"))
        assertEquals(RuleType.DEVICE_NAME, RuleType.parse(23))
        assertNull(RuleType.parse(24))
        assertEquals(RuleAction.REJECT, RuleAction.parse("reject"))
        assertEquals(RuleAction.PROXY, RuleAction.parse(0))
    }

    @Test
    fun ruleTypeOrdinalsMatchTheCSharpEnum() {
        // WPE 按整数序列化枚举：顺序错一位，整张规则表的语义就错了
        val expected = listOf(
            "DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "GEOIP", "GEOSITE", "IP-CIDR", "IP-CIDR6",
            "SRC-IP-CIDR", "SRC-PORT", "DST-PORT", "PROCESS-NAME", "PROCESS-PATH", "NETWORK", "RULE-SET", "MATCH",
            "AND", "OR", "NOT", "SUB-RULE", "IN-PORT", "UI-EX", "COMMAND", "DEVICE-NAME",
        )
        assertEquals(expected, RuleType.entries.map { it.keyword })
        assertEquals(listOf("PROXY", "REJECT", "DIRECT"), RuleAction.entries.map { it.name })
    }

    @Test
    fun subscriberAcceptsCamelAndPascalCase() {
        val camel = """{"success":true,"message":null,"data":{"subscriberName":"WPC-1","ipAddress":"1.2.3.4","port":88},"code":0}"""
        val pascal = """{"Success":true,"Data":{"SubscriberName":"WPC-1","IPAddress":"1.2.3.4","Port":88},"Code":0}"""
        for (json in listOf(camel, pascal)) {
            val s = Parse.subscriber(json)!!
            assertEquals("1.2.3.4", s.ipAddress)
            assertEquals(88, s.port)
        }
        assertNull("Data 为 null = 订阅号不存在或已过期", Parse.subscriber("""{"success":false,"data":null}"""))
        assertNull(Parse.subscriber("not json"))
    }

    @Test
    fun noticesFromBothServers() {
        val wpe = """[{"NID":"x","NoticeType":2,"NoticeTitle":"维护","NoticeContent":"a\nb","NoticeMore":"","NoticeTime":"2026-09-13T02:00:00+08:00"}]"""
        val n = Parse.notices(wpe).single()
        assertEquals(2, n.noticeType)
        assertEquals("a\nb", n.noticeContent)
        assertEquals("2026-09-13T02:00:00+08:00", n.noticeTime)

        val global = """{"success":true,"data":[{"noticeType":1,"noticeTitle":"活动","noticeContent":"c","noticeMore":"https://www.wpe64.com/","noticeTime":"2026-09-11T10:00:00"}]}"""
        assertEquals("https://www.wpe64.com/", Parse.globalNotices(global).single().noticeMore)
        assertTrue(Parse.globalNotices("""{"success":true,"data":null}""").isEmpty())
    }
}
