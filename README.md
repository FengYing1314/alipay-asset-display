# 支付宝资产显示修改

[![Checks](https://github.com/FengYing1314/alipay-asset-display/actions/workflows/ci.yml/badge.svg)](https://github.com/FengYing1314/alipay-asset-display/actions/workflows/ci.yml)

基于 LSPosed API 102 的 Android 本机显示模块。为已适配的支付宝页面独立设置余额、余额宝、基金汇总和总资产的显示金额；只改变显示文本、绘制副本和朗读描述，不改变真实资产、账户数据、网络响应或支付能力。

**当前为 `v0.1.0` 开发预发布版，尚非稳定版。** 限定支付宝 `12.12.36.8000`（版本代码 `212370`）主进程，未知版本及无法唯一识别的结构保持宿主原样。构建成功不能替代实机验证，覆盖范围与限制见 [验证与诊断](docs/verification.md)。

[下载 v0.1.0 预发布版](https://github.com/FengYing1314/alipay-asset-display/releases/tag/v0.1.0) · [使用与安装](#使用与安装) · [开发与构建](#开发与构建) · [问题反馈](https://github.com/FengYing1314/alipay-asset-display/issues) · [架构说明](docs/architecture.md) · [贡献指南](CONTRIBUTING.md)

## 功能与边界

| 页面       | 显示目标                 | 数量 |
| ---------- | ------------------------ | ---: |
| 理财首页   | 总资产、余额宝、基金汇总 |    3 |
| 总资产详情 | 总额、余额、余额宝、基金 |    4 |
| 余额宝详情 | 顶部总金额               |    1 |
| 基金持有页 | 顶部汇总                 |    1 |
| 余额详情   | 可用余额主金额           |    1 |

四项金额独立保存，总资产不会自动求和。金额直接使用配置值，保留两位小数，不附加可见前缀。不修改基金明细、收益、利率、份额、冻结额或余额宝独立可用金额。隐藏状态优先，页面身份变化后不回放旧数字。

## 使用与安装

需要最低 Android 9（API 28），以及支持 API 102 和远程配置能力的 LSPosed 环境。当前项目提供开发用 Debug 构建，仓库不包含发布签名密钥。

1. 从 [v0.1.0 预发布页](https://github.com/FengYing1314/alipay-asset-display/releases/tag/v0.1.0) 下载 Debug APK 并安装；也可使用下方的 Actions 开发构建或 [自行构建](#开发与构建)。
2. 在 LSPosed 中启用“支付宝资产显示修改”，作用域勾选支付宝。
3. 完全退出并重新打开支付宝，使模块随新进程加载。
4. 打开模块设置，确认框架连接，填写四项显示金额并保存。
5. 重新进入目标页面，核对显示、隐藏恢复和非目标字段。

“保存成功”表示框架确认配置写入，不能据此判断目标页面已应用。设置界面保留未保存草稿，诊断默认关闭，在“高级设置”中展开。绘制链路检查不等同于物理屏幕所有刷新帧的逐像素证明。

### 获取预发布版

打开 [v0.1.0 预发布页](https://github.com/FengYing1314/alipay-asset-display/releases/tag/v0.1.0)，下载 `alipay-asset-display-v0.1.0-debug.apk`；同页的 `SHA256SUMS` 用于核对文件完整性。这是开发预览 Debug 包，不是稳定版。

不同构建环境或 CI 运行可能使用不同 Debug 签名，未必能相互覆盖安装。更新前请记录四项显示配置并核对包名和签名；无法覆盖安装时，先确认原因，再选择适合的迁移方式。旧包名用户还需按 [迁移步骤](#从旧包名迁移) 停用旧模块并重新配置作用域。

### 获取 Actions 构建包

打开 [Checks 工作流](https://github.com/FengYing1314/alipay-asset-display/actions/workflows/ci.yml)，选择一次成功的运行，在页面的 **Artifacts** 区域下载 `alipay-asset-display-debug`，解压后得到 `app-debug.apk`。下载需要登录 GitHub；构建产物保留 7 天，过期后可选择更新的成功运行或自行构建。

Actions 产物用于获取更新的开发构建，同样适用上述 Debug 签名和配置保留要求。

### 从旧包名迁移

旧包名 `io.github.fengying.alipaydisplaydemo` 已改为 `io.github.fengying.alipaydisplay`。Android 将新包名视作独立应用，新 APK 不会覆盖旧应用；旧应用的本机草稿和 LSPosed 模块配置不会自动迁移。

1. 需要时先自行记录原来的四项显示配置，并在 LSPosed 停用旧包名模块。
2. 安装新包名 APK，启用“支付宝资产显示修改”，作用域勾选支付宝，避免两个模块同时生效。
3. 确认框架连接，重新填写四项金额并保存。
4. 完全退出并重新打开支付宝，再验证目标页面。

旧模块是否卸载由使用者自行决定。新包名的实机验收范围以 [验证记录](docs/verification.md) 为准。

## 模块标识

| 项目                             | 当前值                                              |
| -------------------------------- | --------------------------------------------------- |
| 应用名称                         | 支付宝资产显示修改                                  |
| Android 应用包名 / Java 命名空间 | `io.github.fengying.alipaydisplay`                  |
| LSPosed Java 入口                | `io.github.fengying.alipaydisplay.ModuleEntry`      |
| 设置页 Activity                  | `io.github.fengying.alipaydisplay.SettingsActivity` |

## 开发与构建

| 工具             | 要求                                           |
| ---------------- | ---------------------------------------------- |
| JDK              | 21，Java 源码与字节码目标为 17                 |
| Android SDK      | `platforms;android-37.2`、`build-tools;37.0.0` |
| Gradle / AGP     | 仓库固定为 9.6.0 / 9.5.0，使用自带 wrapper     |
| Node.js / Python | Node.js 26、Python 3.9+                        |

先配置本机 `JAVA_HOME`、`ANDROID_HOME`，或通过未纳入版本管理的 `local.properties` 指定 SDK。

```bash
git clone https://github.com/FengYing1314/alipay-asset-display.git
cd alipay-asset-display
npm ci --ignore-scripts
sh scripts/check.sh --android
```

统一检查入口包含文本规范、格式、JavaScript 语法和边界测试，以及 Java 单元测试、Debug 构建和 Android Lint。只检查源码和脚本时运行 `sh scripts/check.sh`，不需要 Android SDK 或设备。

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。首次获取依赖和格式工具需要网络，缓存齐全后可离线运行 Android 检查：

```bash
./gradlew --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

```bash
npm run format        # 应用统一格式
npm run format:check  # 只检查，不重写源码
```

Java 使用固定版本的 AOSP 格式；JavaScript、JSON、YAML、Markdown 使用锁定版本的 Prettier。完整环境、命名、注释和函数排列要求见 [贡献指南](CONTRIBUTING.md)。[GitHub Actions](https://github.com/FengYing1314/alipay-asset-display/actions) 执行源码检查和 Android 构建，不连接手机或验证实际注入结果。

## 项目结构

```text
app/src/main/java/       设置、配置、框架入口与页面会话
app/src/main/res/        设置页布局、文案与深浅色主题
app/src/main/resources/  Web 显示脚本、模块元数据与第三方许可
app/src/test/            Java 纯策略与 JavaScript DOM 边界测试
app/src/visualState*/    SDK 回调桥源码及不打包的编译桩
docs/                   架构、验证范围与诊断说明
scripts/                格式与检查入口
.github/                CI、依赖更新及问题/PR 模板
```

## 参与项目

- 提问或报告问题：[Issues](https://github.com/FengYing1314/alipay-asset-display/issues)，请包含版本和可复现步骤。
- 提交改动：[贡献指南](CONTRIBUTING.md) 与 [行为准则](CODE_OF_CONDUCT.md)。
- 涉及漏洞或隐私：[安全政策](SECURITY.md)。
- 查看行为变化：[CHANGELOG.md](CHANGELOG.md)。

使用合成或脱敏的复现信息，不上传真实金额、账户信息、完整 URL、设备转储或签名材料。

## 许可证

项目代码采用 [MIT](LICENSE)。第三方依赖保留其原许可证，详见 [第三方声明](THIRD_PARTY_NOTICES.md)。本项目与支付宝及其关联主体没有隶属或背书关系，相关应用及商标归各自权利人所有。
