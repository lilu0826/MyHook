# 丰巢 SDK 实机分析（2026-11-06）

设备：Android 17 / API 37，LSPosed v2.2.1 (7912)，实际丰巢 6.48.0 / 6048000。原始 base.apk 为 6.45.0；因此本次新增规则以运行时签名为依据。最终已安装 MyHook 0.2.0 / versionCode 4。

## 已获得的证据

1. 从 `Application.attach` 返回后的真实 ClassLoader 可以找到解壳后的业务类，读取方法签名。无需修改原 APK。
2. 第三方入口存在：TopOn ATSDK、ToBid WindMillAd、优量汇 GDTAdSdk、穿山甲 TTAdSdk、快手 KsAdSDK 等。类存在、Hook 安装成功均不证明它们已经初始化。
3. 找到业务封装 `kt.com.fcbox.hiveconsumer.app.business.ad.AppAdSdkManager`、`ToponImpl`、`TobidImpl`、`UbixImpl`，以及首页横幅、插屏、开屏的 Client / Agent / Listener 类型。
4. 临时放行原开屏流程作对照时，实际命中了 `AppAdvertModel`、`AdRecommendSwitchManager`、`AdEventManager` 等业务方法；未捕获已探测第三方 SDK 的初始化/加载调用。`AppAdvertModel.t(Continuation)` 最终返回 `AdResp` 类型，这只能说明存在广告业务数据流程，不能推断响应内容或广告成功加载。
5. 用户反馈开屏广告间歇出现，本次无法稳定复现。服务端下发、缓存、频控可能影响展示，但未读取相应数据，具体原因未确定。

追踪日志及设备专用采集脚本仅保存在本地，不随仓库发布。诊断只记录方法签名、调用栈、返回类型与布尔值，不记录调用参数值、账号、URL 或令牌。

## 本次启用的六个 Hook

以下类名均位于 `kt.com.fcbox.hiveconsumer.app.business.ad` 下。

| 入口 | 已核实的契约 | 拦截行为 |
| --- | --- | --- |
| `impl.ToponImpl.a` | `(Context, InitCallback, Continuation<? super Unit>) -> Object` | 不执行初始化封装，调用 `onFailed(String)`，同步返回宿主的 Unit |
| `impl.TobidImpl.a` | 同上 | 同上 |
| `impl.UbixImpl.a` | 同上 | 同上 |
| `AppAdSdkManager.m` | `List<HomeBannerAdClient>, BannerAdvertListener, Function1<List<HomeBannerClientResult>, Unit>`，返回 void | 新空列表替换第一个参数，保留原方法执行 |
| `AppAdSdkManager.n` | `List<InterstitalAdClient>, AdvertListener, Function1<List<InterstitalClientResult>, Unit>`，返回 void | 同上 |
| `AppAdSdkManager.p` | `List<SplashAdClient>, AdvertListener, Function1<List<SplashClientResult>, Unit>`，返回 void | 同上 |

`AdSdkInitInterface.InitCallback` 实际声明了 `onFailed(String)` 和 `onSuccess()`。模块只使用失败回调，不伪造成功。初始化方法的挂起返回类型是 Unit，不是 Boolean。同步返回 Unit 后不再调用 Continuation.resumeWith，符合 Kotlin 的同步完成规则。[Kotlin 协程规范](https://kotlinlang.org/spec/asynchronous-programming-with-coroutines.html)

实机中 Unit 的 `INSTANCE` 字段已被混淆；模块解析其唯一的 static final Unit 类型字段，避免猜混淆字段名。解析或方法签名不匹配则不安装该条规则。方法存在性和参数类型校验仍有必要，这与按版本号限制不同。

批量广告入口使用新列表，不修改调用方原列表。保留原方法，是为了让原有调度和回调逻辑有机会正常完成；尚未获得其方法体，不能保证空列表路径的全部业务行为，仍需真实触发验证。

## 最终验证与限制

- 手工构建成功，原有 12 项 LaunchPolicy 测试通过，APK v2/v3 签名与 zipalign 检查通过。
- ADB 同签名覆盖安装成功。
- 最终进程日志 `myhook-0.2.0.txt` 确认六条 `ADBLOCK installed`，没有 `ADBLOCK unavailable`。
- 同一日志确认 `EntranceDelegateActivity -> SplashActivity` 被重定向到 MainActivity；系统 resumed Activity 为 MainActivity。
- 最终构建恢复开屏跳过，`SDK_DIAGNOSTICS=false`。最终日志无大范围 ADPROBE 输出。
- 本次没有 `ADBLOCK hit`，所以只确认规则就绪及冷启动正常，尚未确认真实广告的失败回调、候选列表过滤效果、重试或备用广告源行为。
- 尚未执行支付、扫码取件等完整回归，也未抓包，不能声称所有 SDK 停止联网。
- 仅主进程和上述业务入口受影响；直接 SDK 调用、其他初始化路径、自动初始化组件、独立子进程、WebView 广告等不保证覆盖。
- 首页商品推荐、查价返现、福利浮层未另外隐藏。用户明确优先处理 SDK。

后续广告再出现时，区分 `ADBLOCK installed`（已挂钩）与 `ADBLOCK hit`（实际命中），同时保留页面类名和出现时机，再补充具体规则。如果出现业务异常，在 LSPosed 中停用 MyHook 后强行停止并重开丰巢即可恢复原流程。
