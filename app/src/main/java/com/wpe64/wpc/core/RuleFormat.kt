package com.wpe64.wpc.core

/*
  节点规则 → mihomo 规则行。与 Windows 版 ProxyService.FormatRule <b>同一套口径</b>，外加手机上才有的一条：

    · RULE-SET / SUB-RULE（缺配套配置）、UI-EX / COMMAND / DEVICE-NAME（不是 mihomo 的类型）→ 跳过；
    · PROCESS-NAME / PROCESS-PATH → 跳过（手机上没有 Windows 的进程名，按应用分流由「分应用代理」做）；
    · 参数为空（MATCH 除外）→ 跳过；
    · 参数带逗号 → 跳过，两种例外：AND / OR / NOT 的括号表达式原样写；「地址,no-resolve」把 no-resolve 挪到动作后面；
    · WPE 发来了认不出的类型值或动作值 → 跳过。

  mihomo 只要有一条规则不认，整份配置加载失败 —— 所以宁可跳过、记日志，也不能原样写进去。
  最后还有一道兜底：ProxyService 用内核自己的解析器（Wpccore.validate）逐条再验一遍。
*/
object RuleFormat {

    private val unsupported = setOf(RuleType.RULE_SET, RuleType.SUB_RULE, RuleType.UI_EX, RuleType.COMMAND, RuleType.DEVICE_NAME)
    private val windowsOnly = setOf(RuleType.PROCESS_NAME, RuleType.PROCESS_PATH)
    private val logical = setOf(RuleType.AND, RuleType.OR, RuleType.NOT)
    private val noResolve = setOf(RuleType.IP_CIDR, RuleType.IP_CIDR6, RuleType.GEOIP)

    /** line 不为 null 时是一条可以写进 rules: 的规则（不含前面的「  - 」）；为 null 时 reason 说明为什么跳过。 */
    data class Result(val line: String?, val reason: String)

    fun format(rule: RuleInfo): Result {
        val type = rule.type ?: return Result(null, "不认识的规则类型（${rule.typeRaw}）")
        val action = rule.action ?: return Result(null, "不认识的动作")

        if (type == RuleType.MATCH) return Result("${type.keyword},${action.name}", "")
        if (type in unsupported) return Result(null, "内核不支持这种规则类型")
        if (type in windowsOnly) return Result(null, "手机上没有进程名，请改用「分应用代理」")

        val arg = rule.argument.trim()
        if (arg.isEmpty()) return Result(null, "参数为空")

        if (',' in arg && type !in logical) {
            val parts = arg.split(',')
            if (parts.size == 2 && type in noResolve && parts[0].isNotBlank() && parts[1].trim().equals("no-resolve", ignoreCase = true)) {
                return Result("${type.keyword},${parts[0].trim()},${action.name},no-resolve", "")
            }
            return Result(null, "参数里不能带逗号")
        }

        return Result("${type.keyword},$arg,${action.name}", "")
    }

    /** 规则的原样描述，写日志用：「DOMAIN-SUFFIX,qq.com」。 */
    fun describe(rule: RuleInfo): String = "${rule.type?.keyword ?: rule.typeRaw},${rule.argument}"
}
