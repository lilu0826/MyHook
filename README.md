# MyHook

丰巢开屏跳过与广告 SDK 定向拦截学习模块，使用 **libxposed API 102**。页面和资源规则来自丰巢 6.45.0，新增 SDK 规则来自手机上 6.48.0 的运行时签名，不限制目标 App 版本号。

**当前版本：0.2.0（versionCode 4），SDK 定向拦截试用版。** 已通过 ADB 覆盖安装，在 Android 17 / LSPosed 2.2.1 / 丰巢 6.48.0 上确认六个新增 Hook 安装成功、冷启动跳过开屏并进入首页。尚未捕获实际广告触发时的新增拦截命中，不能据此认定全部 SDK 已停用。

## 安装

1. 按下方说明编译，安装生成的 `dist/MyHook-0.2.0.apk`，可覆盖安装此前同签名的 MyHook。这是独立模块，不需要覆盖安装丰巢。仓库不提交 APK 和签名密钥。
2. 在 LSPosed 中启用 **MyHook**，确认作用域为 **丰巢 / com.fcbox.hiveconsumer**。
3. 强行停止丰巢，再从桌面图标重新打开。若框架提示重启，按提示操作。
4. 模块没有桌面入口和设置页，在 LSPosed 的模块列表里管理。

要求：Android 8.0 以上、支持 libxposed API 102 的框架、丰巢 `com.fcbox.hiveconsumer`。模块在丰巢主进程中按页面类名和资源名称匹配，不再检查 `versionCode` 或 `versionName`。新版丰巢是否适用取决于这些页面和流程是否仍兼容。

## 本版做什么

- 保留 `EntranceDelegateActivity` 的正常初始化。
- 当入口页、首页或已确认的开屏页，通过普通 `startActivity` 启动三个明确的 Splash Activity 时，尝试将目标改成 `MainActivity`。
- 用白色/透明 Drawable 替换应用进程中的 `bg_launcher`、`bg_entrance`、`bg_entrance_logo`。
- 在启动页生命周期回调附近清理背景和三个已确认的启动图控件。
- 记录页面类名与跳转，不记录 Intent 内容、账号或令牌。
- 在解壳后的 `Application.attach` 返回时安装 SDK 规则，Activity 创建前补试。
- 拦截 `ToponImpl`、`TobidImpl`、`UbixImpl` 的初始化封装，调用业务 `onFailed(String)`，以实际 `kotlin.Unit` 单例同步完成挂起函数，避免把等待回调的初始化方法直接置空。
- 对 `AppAdSdkManager.m/n/p` 接收的首页横幅、插屏、开屏候选列表传入新空列表，再执行原方法，保留原有调度和完成回调。空列表分支的完整业务表现仍需广告触发时验证。
- 日常关闭 `SDK_DIAGNOSTICS`，不枚举 dex、不大量输出调用栈。规则命中日志每个 Hook 每个进程最多输出五次。

SDK 规则限定于上述业务入口，不能覆盖所有直接调用、自动初始化、子进程或 WebView 页面。方法签名不匹配时跳过对应规则；仍然不检查 App 版本。首页商品推荐和福利浮层未做额外隐藏。

不能把“资源已替换”等同于“启动没有等待”：入口页的初始化和网络等待仍可能存在。Android 系统启动预览也可能短暂显示，因为本模块只作用于丰巢进程。

## 代码从哪里看

- `app/src/main/java/dev/josh/myhook/MyHook.java`：模块入口、方法 Hook、图片替换。
- `app/src/main/java/dev/josh/myhook/LaunchPolicy.java`：目标类名和跳转条件，适合先读。
- `app/src/main/java/dev/josh/myhook/AdSdkBlocker.java`：六个 SDK 定向拦截点及回调契约检查。
- `app/src/main/java/dev/josh/myhook/AdSdkProbe.java`：默认关闭的诊断工具；仅记录类、方法签名、调用栈和返回类型/布尔值。
- `app/src/main/resources/META-INF/xposed/`：API 102 入口及作用域配置。
- [启动分析报告](docs/分析报告.md)：APK 中已确认的证据、加固限制和待验证项。
- [广告屏蔽方案](docs/广告屏蔽方案.md)：可见广告定位与 SDK 分析；末尾记录实机结果。
- [SDK 实机分析](docs/SDK实机分析.md)：6.48.0 的实际接口、拦截范围与验证限制。

分析说明中引用的原始 APK、解包目录、截图和手机日志仅保存在本地，不随仓库发布。

## 编译

当前安装包使用 Android 官方 Build Tools 35、Platform 35、JDK 17 和 Maven Central 的 `io.github.libxposed:api:102.0.0` 编译。

Windows 安装 JDK 17 并设置 `JAVA_HOME` 后，在仓库根目录执行：

```powershell
.\bootstrap-tools.ps1
.\build.ps1
```

也可以向 `build.ps1` 传入 `-Jdk <JDK 17 安装目录>`。下载脚本使用 `curl.exe`；依赖默认保存在上一级 `.tools`，不会全局安装 SDK。SDK 压缩包校验值来自 Google 仓库元数据，API AAR 的 SHA-256 固定为本次获取的制品值。

也提供标准 Android Studio / Gradle 工程：JDK 17、Android SDK 35、Gradle 8.9，执行 `gradlew.bat :app:assembleDebug`。该 Gradle 路径尚未执行验证，本次交付包来自已验证的 `build.ps1`。

`build.ps1` 首次运行会在本地生成学习用签名密钥 `myhook-debug.jks`，密码 `android`，别名 `myhook`。该文件已被 Git 忽略，请自行保留以继续签名安装更新。不同电脑新生成的密钥及 Gradle 默认 debug 密钥均可能不同，不能直接覆盖安装另一密钥签名的旧 MyHook。

## 验证与排查

先正常完成丰巢首次启动所需的操作，再测试：

1. 冷启动：强行停止后打开，检查是否还有全屏广告或启动图片。
2. 返回测试：进入首页后按返回，不应再次回到开屏页面。
3. 热启动：切到后台后再回来，检查是否弹出另一种广告。
4. 原有入口：检查扫码、取件、登录和外部链接是否照常可用。

在 LSPosed 日志中搜索 `MyHook`，预期看到：

```text
Loaded API=102; target=com.fcbox.hiveconsumer
Route ...EntranceDelegateActivity -> ...SplashActivity; requestCode=-1
Redirect ...SplashActivity -> MainActivity
Replace drawable bg_launcher
```

不同运行路径不一定出现所有行。若仍有广告，请保留包含 `MyHook` 的日志，并说明 Android 版本、广告出现于冷启动还是返回前台；据此再找准确的业务 Hook 点。

新增日志含义：`ADBLOCK installed` = Hook 安装成功；`ADBLOCK hit init` = 实际拦截初始化；`ADBLOCK hit candidates` = 实际清空一批广告候选；`ADBLOCK unavailable` = 该规则未能安装。不要把 `installed` 当成 `hit`。

这台 LSPosed 2.2.1 将日志统一写入 `LSPosedFramework` 标签，消息正文才包含 `MyHook`。安卓终端可运行：

```sh
su -c 'logcat -v time -s LSPosedFramework' | grep --line-buffered MyHook
```

若终端的 grep 不支持 `--line-buffered`，去掉该参数。也可直接在管理器日志中搜索 `MyHook`；本机框架文件位于 `/data/adb/lspd/log/modules_*.log`。Android 开发者选项中的日志开关需要打开。

若看到 `requestCode` 为非负数，模块会保留原流程，以免破坏返回结果契约。若实际使用 `AdvertActivity`、Fragment、入口页内嵌广告或特殊路由，本版也可能不生效。

若发生闪退或跳转异常，在 LSPosed 停用 MyHook，强行停止丰巢后重新打开即可；本模块没有修改原 APK 或写入丰巢偏好设置。

## 已完成的检查

- Java 源码对照真实 API 102 AAR 编译成功。
- 12 项跳转策略测试通过：冷/热启动、作用域、深链、选择器、结果回传、登录、引导页及首页图片边界。
- D8 编译、资源打包、zipalign 检查通过。
- APK v2/v3 签名验证通过，名称为 MyHook。
- 打包后反编译检查入口存在；没有把 libxposed API 类打入 APK。
- 模块入口、API 配置和作用域文件检查通过。
- ADB 覆盖安装 0.2.0 成功；实机确认六个 SDK Hook 安装成功、开屏跳转首页成功。
- 尚未验证新增拦截真实命中、支付结果页、扫码取件以及全部 SDK 网络请求；不能用本次冷启动检查替代这些验证。

API 写法与入口配置参考 [libxposed 官方文档](https://libxposed.github.io/api/io/github/libxposed/api/package-summary.html)。系统启动画面的机制参考 [Android 官方说明](https://developer.android.com/develop/ui/views/launch/splash-screen)。
