package wpccore

import (
	"os"
	"strings"
	"testing"
)

// 用内核自己的解析器验 Android 的配置模板（与 App 运行时读的是同一个文件：app/src/main/assets/base-android.yaml）。
// 在 Windows 主机上跑：  cd core && go test -tags with_gvisor,cmfa -run Template ./...
// 只调 Validate（只解析、不应用），不会真的去建 TUN。

func fill(t *testing.T, rules ...string) string {
	t.Helper()
	raw, err := os.ReadFile("../app/src/main/assets/base-android.yaml")
	if err != nil {
		t.Fatalf("read template: %v", err)
	}
	s := string(raw)
	for _, ph := range []string{"{server}", "{port}", "{username}", "{password}"} {
		if !strings.Contains(s, ph) {
			t.Fatalf("template lost placeholder %s", ph)
		}
	}
	s = strings.NewReplacer(
		"{server}", "'10.172.190.252'",
		"{port}", "1080",
		"{username}", "'player_001'",
		// 令牌的形状：wpc1. + base64url；再塞一个单引号验 YAML 转义
		"{password}", "'wpc1.aV4hDActthqUVsZh9MfeiJ3JJivjYRkh-VcYpCuT0Rc''x'",
	).Replace(s)

	var b strings.Builder
	b.WriteString(strings.TrimRight(s, "\r\n"))
	b.WriteString("\n\nrules:\n")
	for _, r := range rules {
		b.WriteString("  - ")
		b.WriteString(r)
		b.WriteString("\n")
	}
	return b.String()
}

func TestTemplateAcceptedByKernel(t *testing.T) {
	cfg := fill(t,
		"DOMAIN-SUFFIX,qq.com,DIRECT",
		"IP-CIDR,1.2.3.0/24,PROXY,no-resolve",
		"AND,((DOMAIN,baidu.com),(NETWORK,UDP)),DIRECT",
		"DST-PORT,443,PROXY",
		"MATCH,PROXY",
	)
	if msg := Validate(cfg); msg != "" {
		t.Fatalf("kernel rejected the Android template: %s\n---\n%s", msg, cfg)
	}
}

func TestKernelRejectsWhatRuleFormatSkips(t *testing.T) {
	// 这几种 RuleFormat.kt 会跳过：确认内核确实不认（否则跳过就是多余的，反过来说明筛选规则写错了）
	for _, bad := range []string{
		"RULE-SET,abc,PROXY",
		"IP-CIDR,1.2.3.0/24,no-resolve,PROXY",
		"UI-EX,whatever,PROXY",
	} {
		if msg := Validate(fill(t, bad, "MATCH,DIRECT")); msg == "" {
			t.Errorf("kernel unexpectedly accepted %q", bad)
		}
	}
}

func TestVersionMentionsKernelTag(t *testing.T) {
	if !strings.Contains(Version(), KernelVersion) {
		t.Fatalf("Version() = %q, want it to contain %s", Version(), KernelVersion)
	}
}
