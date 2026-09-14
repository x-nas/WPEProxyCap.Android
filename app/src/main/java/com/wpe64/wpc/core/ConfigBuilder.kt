package com.wpe64.wpc.core

/*
  生成 mihomo 配置：模板（assets/base-android.yaml）+ 占位符 + rules: 段。
  与 Windows 版 ProxyService.CreateConfigFileAsync / BuildRulesSection 对应。
*/
object ConfigBuilder {

    data class Input(
        val server: String,
        val port: Int,
        val username: String,
        /** 新版 WPE 是控制通道发的令牌（wpc1.…），老版 / 没开认证是原密码 */
        val password: String,
    )

    /** 一条被跳过的规则：原样描述 + 原因（写日志）。 */
    data class Skipped(val rule: String, val reason: String)

    data class Rules(val lines: List<String>, val skipped: List<Skipped>)

    /**
     * YAML 单引号字符串：密码里一个 # 就会被当注释截断，以 * / & / ! 开头会被当成别名 / 锚点 / 标签，
     * 纯数字或 yes/no 还会被读成别的类型。单引号内的单引号写两个。
     */
    fun yamlQuote(s: String): String = "'" + s.replace("'", "''") + "'"

    /** 先按 RuleFormat 的口径筛一遍。节点没配规则、或者全被筛掉了：全部直连（与 Windows 版一致）。 */
    fun formatRules(rules: List<RuleInfo>): Rules {
        val lines = ArrayList<String>()
        val skipped = ArrayList<Skipped>()
        for (r in rules) {
            val res = RuleFormat.format(r)
            if (res.line != null) lines += res.line else skipped += Skipped(RuleFormat.describe(r), res.reason)
        }
        if (lines.isEmpty()) lines += "${RuleType.MATCH.keyword},${RuleAction.DIRECT.name}"
        return Rules(lines, skipped)
    }

    fun build(template: String, input: Input, ruleLines: List<String>): String {
        val body = template
            .replace("{server}", yamlQuote(input.server))
            .replace("{port}", input.port.toString())
            .replace("{username}", yamlQuote(input.username))
            .replace("{password}", yamlQuote(input.password))

        val sb = StringBuilder(body.trimEnd()).append("\n\nrules:\n")
        for (l in ruleLines) sb.append("  - ").append(l).append('\n')
        return sb.toString()
    }

    /**
     * 用内核自己的解析器兜底：整份配置验不过时逐条验，把内核不认的规则摘掉。
     * validate 返回空串表示通过（对应 Wpccore.validate）。
     * 返回最终留下的规则与这一步额外摘掉的规则。
     */
    fun filterWithKernel(
        template: String,
        input: Input,
        lines: List<String>,
        validate: (String) -> String,
    ): Rules {
        if (validate(build(template, input, lines)).isEmpty()) return Rules(lines, emptyList())

        val kept = ArrayList<String>()
        val dropped = ArrayList<Skipped>()
        val fallback = "${RuleType.MATCH.keyword},${RuleAction.DIRECT.name}"

        for (l in lines) {
            // 单条验：只放这一条（MATCH 本身就是兜底，不用再补）
            val probe = if (l.startsWith("MATCH,")) listOf(l) else listOf(l, fallback)
            val err = validate(build(template, input, probe))
            if (err.isEmpty()) kept += l else dropped += Skipped(l, err.lineSequence().firstOrNull()?.take(160) ?: err)
        }

        if (kept.isEmpty()) kept += fallback
        return Rules(kept, dropped)
    }
}
