# 第三方组件与许可证

WPEProxyCap.Android 整体按 **GNU General Public License v3.0** 分发（全文见 `LICENSE`）。

APK 里包含下列第三方组件：

| 组件 | 版本 | 用途 | 许可证 | 源码 |
|---|---|---|---|---|
| mihomo（Clash Meta 内核） | v1.19.21，未修改 | 进程内代理与规则引擎，经 `core/` 的 gomobile 封装编进 `app/libs/wpccore.aar` | GPL-3.0 | https://github.com/MetaCubeX/mihomo/tree/v1.19.21 |
| mihomo 的 Go 依赖 | 见 `core/go.mod`、`core/go.sum` | 同上 | 各自的许可证（MIT / BSD / Apache-2.0 / GPL 等） | 按 `go.sum` 锁定的版本 |
| WPEProxyCap.Web | 见 `app/src/main/assets/www/web-commit.txt` | 界面前端（Vue 3） | MIT | https://github.com/x-nas/WPEProxyCap.Web |
| Vue | 3.5 | 前端框架（已打包进上面的前端） | MIT | https://github.com/vuejs/core |
| AndroidX Core / Activity / WebKit | 见 `app/build.gradle.kts` | 系统能力封装 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| kotlinx.coroutines | 1.10.2 | 协程 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| Orbitron、Share Tech Mono、JetBrains Mono、更纱黑体 | 前端自带 | 界面字体 | SIL Open Font License 1.1 | 见前端仓库 `src/assets/fonts/` |

## 对应源码

每个发布版本打 tag。重建一个发布版本需要：

1. 本仓库该 tag 的源码（含 `core/` 与 `tools/build-core.sh`，它们决定内核怎么编）；
2. `web-commit.txt` 里记下的 WPEProxyCap.Web 提交；
3. 上表中 mihomo 的 tag 与 `core/go.sum` 锁定的依赖版本。

订阅服务器的 ApiKey 不在源码里（构建时从 `local.properties` 的 `wpc.apiKey` 注入）。自行编译的版本拿不到它，
能连接 WPE、能用本机调试订阅号，但无法向官方订阅服务器兑换订阅号。
