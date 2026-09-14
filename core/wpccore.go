// Package wpccore 是 WPEProxyCap.Android 进程内 mihomo 内核的薄封装，由 gomobile bind 生成 AAR。
//
// Windows 版 WPC 把 mihomo 当子进程（wpe-mihomo.exe）拉起、经 127.0.0.1:9090 的 external-controller 取流量；
// Android 上没有子进程可用，内核直接跑在 App 进程里：
//
//   - TUN：VpnService 建好隧道后把文件描述符传进来，写进配置的 tun.file-descriptor；
//     路由、DNS 劫持由 VpnService.Builder 负责，mihomo 不碰系统路由（auto-route / auto-detect-interface 都关）。
//   - 回环：App 自己用 addDisallowedApplication 排除出 VPN，内核发出的连接（到 WPE 的 SOCKS5、到 DNS）天然不进隧道，
//     不需要逐个套接字 protect。
//   - 流量 / 内存：直接读 tunnel/statistic，不开 external-controller（手机上没必要多开一个监听端口）。
//
// 构建标签必须带 cmfa：不带时 sing-tun 在 Android 上会去读系统的包管理数据库（普通 App 没权限），隧道起不来。
// 带上之后行为与 ClashMetaForAndroid 一致（关掉进程查找与回环探测，这两样本来就由 VpnService 那一层负责）。
//
// gomobile 只能导出基础类型：字符串、整数、布尔、error 与接口回调。
package wpccore

import (
	"runtime"
	"runtime/debug"
	"strings"
	"sync"

	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/listener"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

// KernelVersion 是编进来的 mihomo 版本，与 Windows 版 wpe-mihomo.exe 同一个 tag（改的时候两边一起改）。
const KernelVersion = "v1.19.21"

// LogSink 接内核日志。Kotlin 侧实现这个接口（gomobile 生成 Java 接口）。
type LogSink interface {
	OnLog(level string, message string)
}

var (
	mu      sync.Mutex
	running bool

	sinkMu sync.Mutex
	sink   LogSink
	logSub <-chan log.Event
)

// Version 形如「Mihomo Meta v1.19.21 android arm64」，界面的「API 检测」直接显示它。
func Version() string {
	return "Mihomo Meta " + KernelVersion + " android " + runtime.GOARCH + " " + runtime.Version()
}

// SetHomeDir 设置内核的工作目录（geodata、缓存都落在这里）。在 Start / Validate 之前调一次。
func SetHomeDir(dir string) {
	if dir != "" {
		C.SetHomeDir(dir)
	}
}

func parse(configYaml string, tunFd int) (*config.Config, error) {
	cfg, err := executor.ParseWithBytes([]byte(configYaml))
	if err != nil {
		return nil, err
	}
	if tunFd > 0 {
		cfg.General.Tun.Enable = true
		cfg.General.Tun.FileDescriptor = tunFd
	}
	return cfg, nil
}

// Start 用一份完整的配置（YAML）启动内核；tunFd 是 VpnService 建好的隧道描述符（<= 0 表示不接隧道，只起规则引擎）。
func Start(configYaml string, tunFd int) error {
	mu.Lock()
	defer mu.Unlock()

	cfg, err := parse(configYaml, tunFd)
	if err != nil {
		return err
	}

	executor.ApplyConfig(cfg, true)
	running = true
	return nil
}

// Reload 在不重建隧道的前提下换配置 —— 用于控制通道重连后换令牌（SOCKS5 密码）。
// tun 段与上次相同（同一个描述符）时，mihomo 不会重建 TUN 监听，已建立的连接不受影响。
func Reload(configYaml string, tunFd int) error {
	mu.Lock()
	defer mu.Unlock()

	if !running {
		return errNotRunning
	}

	cfg, err := parse(configYaml, tunFd)
	if err != nil {
		return err
	}

	executor.ApplyConfig(cfg, false)
	return nil
}

// Stop 停内核：关掉所有监听（含 TUN）与出站连接。之后可以再次 Start。
func Stop() {
	mu.Lock()
	defer mu.Unlock()

	if !running {
		return
	}

	executor.Shutdown()
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		_ = c.Close()
		return true
	})

	// ⚠️ Shutdown 只关监听，不清 listener.LastTunConf。下一次 Start 时新隧道的描述符号码很可能与上次相同
	// （内核会复用最小的空闲号），tun 段于是与「上一次」相等，mihomo 判定无需重建 —— 结果隧道根本没接上。
	// 这里用一份关闭的 tun 配置走一遍 ReCreateTun，把缓存清成空的。
	listener.ReCreateTun(LC.Tun{}, tunnel.Tunnel)

	running = false
	debug.FreeOSMemory()
}

// IsRunning 内核是否在跑。
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

// Validate 只解析不应用：返回空串表示这份配置能被内核接受，否则返回错误文字。
// 用来在启动前逐条筛掉内核不认的规则（节点规则是在 WPE 里给 Windows 版配的，手机上可能有不支持的类型）。
func Validate(configYaml string) string {
	if _, err := executor.ParseWithBytes([]byte(configYaml)); err != nil {
		return err.Error()
	}
	return ""
}

// TrafficUp 最近一秒的上行字节数。
func TrafficUp() int64 {
	up, _ := statistic.DefaultManager.Now()
	return up
}

// TrafficDown 最近一秒的下行字节数。
func TrafficDown() int64 {
	_, down := statistic.DefaultManager.Now()
	return down
}

// TotalUp 本次启动以来的上行总字节数。
func TotalUp() int64 {
	up, _ := statistic.DefaultManager.Total()
	return up
}

// TotalDown 本次启动以来的下行总字节数。
func TotalDown() int64 {
	_, down := statistic.DefaultManager.Total()
	return down
}

// MemoryInUse 内核统计的内存占用（字节）。
func MemoryInUse() int64 {
	return int64(statistic.DefaultManager.Memory())
}

// SetLogSink 接上（或传 nil 摘掉）日志回调。只保留一个订阅。
func SetLogSink(s LogSink) {
	sinkMu.Lock()
	defer sinkMu.Unlock()

	if logSub != nil {
		log.UnSubscribe(logSub)
		logSub = nil
	}
	sink = s
	if s == nil {
		return
	}

	sub := log.Subscribe()
	logSub = sub
	go func(ch <-chan log.Event, target LogSink) {
		for ev := range ch {
			target.OnLog(ev.Type(), strings.TrimSpace(ev.Payload))
		}
	}(sub, s)
}

type coreError string

func (e coreError) Error() string { return string(e) }

const errNotRunning = coreError("kernel is not running")
