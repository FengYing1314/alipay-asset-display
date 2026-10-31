<p align="center">
  <img src="docs/assets/icon.svg" width="72" height="72" alt="支付宝资产显示修改图标" />
</p>

<h1 align="center">支付宝资产显示修改</h1>

<p align="center">为已适配的支付宝页面，独立设置余额、余额宝、基金汇总和总资产的本机显示金额。</p>

<p align="center">
  <a href="https://github.com/FengYing1314/alipay-asset-display/actions/workflows/ci.yml"><img src="https://github.com/FengYing1314/alipay-asset-display/actions/workflows/ci.yml/badge.svg" alt="Checks" /></a>
  <a href="https://github.com/FengYing1314/alipay-asset-display/releases/tag/v0.1.0"><img src="https://img.shields.io/badge/version-v0.1.0--preview-0F766E" alt="v0.1.0 预发布版" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-0F766E" alt="MIT 许可证" /></a>
</p>

<p align="center">
  <a href="https://github.com/FengYing1314/alipay-asset-display/releases/tag/v0.1.0"><strong>下载 APK</strong></a> ·
  <a href="docs/usage.md">使用指南</a> ·
  <a href="#常见问题">常见问题</a> ·
  <a href="https://github.com/FengYing1314/alipay-asset-display/issues/new/choose">问题反馈</a>
</p>

基于 LSPosed API 102，仅改变本机显示文本、绘制副本和朗读描述，不改变真实资产、账户数据、网络响应或支付能力。

> **当前版本为开发预发布版。** 下载附件使用 Debug 签名，尚非稳定版。当前包名尚未完成实机验收；已有测试与历史设备验证的覆盖范围见 [验证记录](docs/verification.md#当前验证状态)。

## 功能与边界

| 页面       | 可配置的显示位置         |
| ---------- | ------------------------ |
| 理财首页   | 总资产、余额宝、基金汇总 |
| 总资产详情 | 总额、余额、余额宝、基金 |
| 余额宝详情 | 顶部总金额               |
| 基金持有页 | 顶部汇总                 |
| 余额详情   | 可用余额主金额           |

- **四项金额独立保存**：总资产不会自动求和，金额显示保留两位小数。
- **保留隐藏状态**：隐藏优先，页面身份变化后不回放旧数字。
- **限定显示位置**：基金明细、收益、利率、份额、冻结额及余额宝独立可用金额不在修改范围内。

## 兼容要求

| 项目    | 当前要求                                   |
| ------- | ------------------------------------------ |
| Android | Android 9（API 28）及以上                  |
| 框架    | 支持 API 102 及远程配置能力的 LSPosed 环境 |
| 支付宝  | `12.12.36.8000`，版本代码 `212370`         |
| 页面    | 已适配的页面结构与资源版本                 |

支付宝版本相同也可能因页面资源更新而不再匹配。未知版本、未知结构或无法唯一识别的页面保持原样；具体资源版本见 [兼容条件](docs/verification.md#兼容条件)。

## 使用与安装

1. 从 [v0.1.0 预发布页](https://github.com/FengYing1314/alipay-asset-display/releases/tag/v0.1.0) 下载 `alipay-asset-display-v0.1.0-debug.apk` 并安装。
2. 在 LSPosed 中启用“支付宝资产显示修改”，作用域勾选 **支付宝**。
3. 完全退出并重新打开支付宝，使模块随新进程加载。
4. 打开模块设置，确认框架连接，填写四项金额，打开 **启用金额显示**，点击 **保存配置**。
5. 重新进入目标页面，核对显示结果、隐藏与恢复，以及其他字段是否保持原样。

“保存成功”表示框架确认配置写入，目标页面是否应用还需实际查看。完整步骤、金额格式、停用及恢复方式见 [使用指南](docs/usage.md)。

### 获取预发布版

[Release 页面](https://github.com/FengYing1314/alipay-asset-display/releases/tag/v0.1.0) 同时提供 APK 与 `SHA256SUMS`，用于核对下载完整性。校验方法见 [下载与校验](docs/usage.md#下载与校验)。

不同环境生成的 Debug 包可能使用不同签名，未必能覆盖安装。更新前请记录四项配置，并阅读 [更新与签名](docs/usage.md#更新与签名)。

### 获取 Actions 构建包

需要更新的开发构建时，可从成功的 [Checks 运行](https://github.com/FengYing1314/alipay-asset-display/actions/workflows/ci.yml) 下载 APK；需要登录 GitHub，产物保留 7 天。[获取步骤与限制](docs/usage.md#获取-actions-构建包)。

### 从旧包名迁移

旧包名 `io.github.fengying.alipaydisplaydemo` 与当前包名 `io.github.fengying.alipaydisplay` 是两个独立应用，配置不会自动继承。请按 [迁移步骤](docs/usage.md#从旧包名迁移) 记录配置、停用旧模块并重新启用新模块。

## 常见问题

**需要 LSPosed 吗？** 需要支持 API 102 及远程配置能力的框架。单独安装 APK 不会让目标页面生效。

**保存成功，页面为什么没有变化？** 依次检查作用域、支付宝版本和进程是否已重启，再重新进入目标页面。页面资源或结构不匹配时也会保持原样，详见 [排错步骤](docs/usage.md#常见问题)。

**如何恢复原来的显示？** 在模块设置关闭“启用金额显示”并保存，再重新进入目标页面。也可在 LSPosed 停用模块后完全退出并重新打开支付宝，详见 [停用与恢复](docs/usage.md#停用与恢复)。

**可以修改真实余额或付款金额吗？** 不可以。模块只调整指定位置的本机显示，不改变真实资产和支付能力。

## 开发与构建

使用仓库自带 Gradle wrapper 和锁定依赖。工具版本、构建命令、APK 输出位置和离线检查见 [贡献指南](CONTRIBUTING.md#开发环境)。

- [架构说明](docs/architecture.md)：模块职责、配置链路、应用标识与资源布局。
- [验证与诊断](docs/verification.md)：已验证范围、兼容条件及设备排查方法。
- [变更记录](CHANGELOG.md)：版本变化与迁移说明。

## 参与项目

使用问题和兼容反馈请提交 [Issue](https://github.com/FengYing1314/alipay-asset-display/issues/new/choose)，附上版本、复现步骤和预期结果。请使用合成或脱敏信息，不上传真实金额、账户信息、完整 URL、设备转储或签名材料。

欢迎通过 [贡献指南](CONTRIBUTING.md) 参与文档、问题复现与代码改进；参与交流请遵守 [行为准则](CODE_OF_CONDUCT.md)。涉及漏洞或隐私的问题按 [安全政策](SECURITY.md) 报告。

## 许可证

项目采用 [MIT 许可证](LICENSE)，第三方依赖保留其原许可证，见 [第三方声明](THIRD_PARTY_NOTICES.md)。本项目与支付宝及其关联主体没有隶属或背书关系，相关应用及商标归各自权利人所有。
