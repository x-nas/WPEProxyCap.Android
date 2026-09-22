# WPEProxyCap.Android

WPE Proxy Cap（WPC）的 Android 客户端：用手机连接 WPE x64 的内置 SOCKS5 代理服务器，让手机上的游戏与应用经 WPE 转发（WPE 那边可以抓包、改包、走滤镜）。
功能与 Windows 版 WPC 1.1 对应，界面是同一份（共用前端 [WPEProxyCap.Web](https://github.com/x-nas/WPEProxyCap.Web)）。

许可证：**GPL-3.0**（见 `LICENSE`，第三方组件见 `NOTICE.md`）。

## 工作原理

```
订阅号 ──▶ 订阅服务器 ──▶ WPE 地址
WPE /ProxyCap/GetServerList ──▶ 节点（含规则）
SOCKS5 端口，私有方法 0x80 ──▶ 注册设备（账号密码 + 设备标识）──▶ 令牌（控制连接常驻 = 在线）
VpnService 建隧道 ──▶ 进程内 mihomo（TUN 描述符）──▶ SOCKS5（密码位是令牌）──▶ WPE ──▶ 互联网
```

- 内核：mihomo v1.19.31（与 Windows 版 `wpe-mihomo.exe` 同一个 tag），`core/` 用 gomobile 编成 `app/libs/wpccore.aar`，构建标签 `with_gvisor,cmfa`。
- 本应用自己不进隧道（`addDisallowedApplication`），到 WPE 的控制通道与数据连接天然直连，不会绕回自己。
- 控制连接断了（切网、短暂冻结）先静默重注册，拿到新令牌后热换配置，不重建隧道；重试失败才断开。
- 分应用代理：全部应用，或只让选中的应用走代理。

## 界面

共用前端里的手机版界面（`AppMobile.vue` 与 `components/mobile/`，细节见 [WPEProxyCap.Web](https://github.com/x-nas/WPEProxyCap.Web)）：

- **首次使用只填订阅号**；账号密码直接在「加速」页填（有节点后才显示账号卡，找回密码 / 立即注册的地址取自所选节点）。
- 四个标签：**加速**（反应堆核心就是连接按钮，已连接时显示计时与读数）· **节点**（按延迟排序、重新测速，顶部一行「订阅号」用来更换订阅）· **消息**（全部公告一个列表）· **我的**（账号、后台运行、系统日志、系统信息、外观、教程与协议）。
- 加速页与「我的」页按屏幕高度自适应，尽量一屏放下；宽度 ≥ 600（平板 / 横屏）换成左侧导航栏，≥ 840 分两栏。
- 安全验证的结果与进度原生侧只发代码（`ProxyService.verifyProxy` / `testSocks5` / `registerKey`），界面按当前语言翻译；代码表与 Windows 版一致，改一边要改另一边。

## 目录

```
app/
  src/main/java/com/wpe64/wpc/
    core/      纯 Kotlin，不依赖 Android：协议（WpcControlClient）、HTTP（WpcApi）、解析（Models）、规则筛选（RuleFormat）、配置生成（ConfigBuilder）
    service/   ProxyService（连接链、监控、重注册）、ConfigStore（Keystore 加密密码）、DeviceFingerprint、Kernel（内核门面）
    vpn/       WpcVpnService（隧道、前台通知、始终开启 VPN）
    bridge/    Bridge（WebMessageListener 上的 JSON-RPC，报文与 Windows 版相同）
    MainActivity.kt / WpcApp.kt
  src/main/assets/
    base-android.yaml   mihomo 配置模板（Go 测试与 App 运行时读的是同一份）
    www/                前端产物（tools/sync-web.ps1 从 WPEProxyCap.Web 镜像，入库）
    agreements/         用户协议、隐私政策
  src/test/             JVM 单元测试（含对着真 WPE 的 InteropTest，平时跳过）
  libs/wpccore.aar      内核（入库，不装 Go 也能编 APK）
core/                   mihomo 的 gomobile 封装 + 模板测试
tools/
  build-core.sh         编内核 AAR
  sync-web.ps1          同步前端
```

三个仓库并排检出：`WPEProxyCap.Android/`、`WPEProxyCap.Web/`；联调还要 `WinsockPacketEditor/`。

## 构建

需要 JDK 17+、Android SDK（platform 36、build-tools 36）；改内核还要 Go 1.26、NDK r28、gomobile。

```bash
# 可选：改了前端
powershell -ExecutionPolicy Bypass -File tools/sync-web.ps1

# 可选：改了 core/
bash tools/build-core.sh

./gradlew testDebugUnitTest assembleDebug
./gradlew assembleRelease          # 需要 keystore.properties 才会签名
```

**一键出发布包（Windows）**：双击仓库根目录的 `pack.cmd`（= `tools/pack/Pack.ps1`）。它依次检查 `local.properties` / `keystore.properties`、同步前端、跑单元测试、`clean assembleRelease`，再核对签名指纹与密钥一致、APK 里有内核与界面、没有明文 ApiKey，最后输出 `dist/WPC Android v<版本>.apk` 与 `.sha256.txt` 并打开 `dist` 目录。参数 `-SkipWeb`（不重新构建前端）、`-SkipTests`（不跑测试）原样透传。内核 AAR 不在这里重编。

本机配置（都不入库）：

- `local.properties`：`sdk.dir=…`；`wpc.apiKey=…`（订阅服务器的 X-Api-Key，不填也能编译，但无法兑换订阅号；构建时拆成两段异或存进 BuildConfig，APK 里没有明文字符串，由 `service/ApiKey.kt` 运行时拼回）。
- `keystore.properties`：`storeFile` / `storePassword` / `keyAlias` / `keyPassword`。

⚠️ **发布签名的密钥一旦用了就永远不要换。** ANDROID_ID 按签名密钥区分，换密钥发版会让所有用户在 WPE 那边变成新设备，可能撞上账号的设备数上限。

⚠️ 仓库的父目录名含中文时，Android Gradle Plugin 默认拒绝构建；`gradle.properties` 里已加 `android.overridePathCheck=true`（本工程不在 Gradle 里编原生代码）。

## 测试

```bash
./gradlew testDebugUnitTest                      # 协议、解析、规则、配置生成、控制通道（对着假服务器）
cd core && go test -tags with_gvisor,cmfa ./...   # 配置模板交给内核自己的解析器验
```

对着真 WPE 联调：先在 WinsockPacketEditor 仓库编译并运行 `tools/tests/WpcInteropHost.cs`（用法写在文件头），它打印 `READY socks=<端口> echo=<端口>`，然后

```bash
WPC_INTEROP_SOCKS=<端口> WPC_INTEROP_ECHO=<端口> ./gradlew testDebugUnitTest --tests "*InteropTest*" --no-daemon
```

## 系统要求

- Android 8.0（API 26）及以上，arm64-v8a。`pack.cmd` 只输出 arm64 包；`assembleRelease` 另外会出一个 x86_64 包，给不能运行 ARM 应用的模拟器自测用，不对外发布。
- Android System WebView 90 及以上（没有 Google 服务的机器请在应用商店更新 WebView）。
