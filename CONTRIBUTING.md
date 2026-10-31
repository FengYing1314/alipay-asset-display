# 贡献指南

提交问题或改动前，请先阅读 [README](README.md) 的支持范围和验证限制，并遵守 [行为准则](CODE_OF_CONDUCT.md)。安全漏洞的报告方式见 [SECURITY.md](SECURITY.md)。

## 开发环境

| 工具         | 版本或要求                                                         |
| ------------ | ------------------------------------------------------------------ |
| JDK          | 21，Java 源码与字节码目标为 17                                     |
| Android SDK  | `platforms;android-37.2`、`build-tools;37.0.0`                     |
| Gradle / AGP | 9.6.0 / 9.5.0，使用仓库自带 wrapper                                |
| Node.js      | 26，与 CI 保持一致                                                 |
| npm          | 随 Node.js 提供，使用 `npm ci --ignore-scripts` 安装锁定的开发工具 |
| Python       | 3.9 或更新版本，仅使用标准库                                       |

将 `JAVA_HOME` 和 `ANDROID_HOME` 指向本机对应目录，或通过未纳入版本管理的 `local.properties` 配置 SDK。不要提交个人路径、SDK、IDE 缓存或签名密钥。

## 获取源码与构建

```bash
git clone https://github.com/FengYing1314/alipay-asset-display.git
cd alipay-asset-display
npm ci --ignore-scripts
sh scripts/check.sh --android
```

完整检查包括文本规范、Java/JavaScript/文档格式、脚本语法、JavaScript 边界测试，以及 Java 单元测试、Debug 构建和 Android Lint。APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。这是开发用 Debug 包，安装、签名兼容和配置迁移要求见 [使用指南](docs/usage.md)。

首次获取依赖和格式工具需要网络；Java 格式工具由项目脚本核验固定摘要。缓存齐全后，可离线运行 Android 检查：

```bash
./gradlew --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

## Release 构建与签名

正式发行另外验证 Release 变体：

```bash
./gradlew --no-daemon :app:assembleRelease :app:lintRelease
```

没有提供签名环境变量时，这条命令生成 `app/build/outputs/apk/release/app-release-unsigned.apk`，供 CI 检查构建，不可作为正式安装包发布。需要签名时，由本机安全存储向构建进程提供以下四项环境变量：

| 环境变量                        | 含义                         |
| ------------------------------- | ---------------------------- |
| `ALIPAY_RELEASE_STORE_FILE`     | 仓库外的发行密钥文件绝对路径 |
| `ALIPAY_RELEASE_STORE_PASSWORD` | 密钥库密码                   |
| `ALIPAY_RELEASE_KEY_ALIAS`      | 发行密钥别名                 |
| `ALIPAY_RELEASE_KEY_PASSWORD`   | 私钥密码                     |

四项全部提供后，同一构建命令输出 `app/build/outputs/apk/release/app-release.apk`。只提供部分参数会直接失败，不会回退到 Debug 签名。不要将密码写入命令行参数、仓库配置、构建日志或发布附件。

正式版本持续使用同一发行密钥。发布前用 Android SDK 的 `apksigner verify --verbose --print-certs` 核验安装包，将证书 SHA-256 与仓库 [发行证书信息](SIGNING-CERTIFICATE.txt) 对照；同时检查包名、版本和非 Debug 标记，生成 `SHA256SUMS`。源码提交通过 CI 后，Release 附件提供带版本号的 APK、校验文件和证书指纹文件。

CI 不持有发行密钥，仅验证未签名 Release 及 Debug 变体，上传的开发 APK 仍使用 Debug 签名。旧 Debug 包与正式发行包的迁移步骤见 [更新与签名](docs/usage.md#更新与签名)。

## 日常检查与格式

只检查源码和脚本时运行以下命令，不要求设备或 Android SDK：

```bash
sh scripts/check.sh
```

修改 Android 代码后使用上方的 `sh scripts/check.sh --android` 运行完整检查。格式操作独立提供两个入口：

```bash
npm run format        # 应用统一格式
npm run format:check  # 只检查，不重写源码
```

具体格式器版本以 `package-lock.json` 和 `scripts/format.py` 为准。[GitHub Actions](https://github.com/FengYing1314/alipay-asset-display/actions/workflows/ci.yml) 分别执行格式/脚本检查与 Android 测试、构建和 Lint。CI 不连接手机，不修改账户，也不验证宿主界面的实际注入结果；验证证据和限制见 [验证与诊断](docs/verification.md)。

## 项目结构

```text
app/src/main/java/       设置、配置、框架入口与页面会话
app/src/main/res/        设置页布局、文案与深浅色主题
app/src/main/resources/  Web 显示脚本、模块元数据与第三方许可
app/src/test/            Java 纯策略与 JavaScript DOM 边界测试
app/src/visualState*/    SDK 回调桥源码及不打包的编译桩
docs/                   使用、架构、验证范围与诊断说明
scripts/                格式与检查入口
.github/                CI、依赖更新及问题/PR 模板
```

组件职责和显示链路见 [架构说明](docs/architecture.md)。

项目图标原图为 `docs/assets/icon.png`，应用使用相同图像及 Android 自适应图标资源。生成参考与完整提示词见 [图标生成记录](docs/assets/icon-prompt.txt)；调整图标时核对 [Android 自适应图标安全区域](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。

## 代码和注释标准

- 文本统一为无 BOM 的 UTF-8、LF、末尾换行，不保留行末空格；Windows wrapper 保留 CRLF。
- Java 使用固定版本的 google-java-format `--aosp`；JavaScript、JSON、YAML 和 Markdown 使用项目固定版本的 Prettier。其余文件遵循 `.editorconfig`。
- 类和接口使用 `UpperCamelCase`，方法与变量使用 `lowerCamelCase`，常量使用 `UPPER_SNAKE_CASE`。命名说明职责，避免无意义缩写。
- 新注释和文档使用简体中文，重点解释归属、线程、生命周期、兼容性边界及选择原因；保留能够解释约束的注释，不重复代码本身。
- 一个类承担一个明确职责。页面布局与配置保存分离；身份识别、纯策略、宿主写入和诊断分别放在对应组件中。不要把页面特例堆进通用工具类。
- 对外协议、包名、反射类名和字段名属于兼容约束，不能随格式整理改名。新增抽象应减少实际耦合，不为单一调用增加空泛层次。

## 成员顺序与函数组织

Java 类统一按以下顺序阅读：常量与字段、构造函数、生命周期/对外入口、按业务流程分组的内部函数、底层辅助函数、嵌套类型。字段初始值可能依赖前面的字段，整理时保留其初始化顺序。已有清晰的小类不为了增加分组而拆散。

会话和协调器将生命周期、事件接口放在前面；内部按配置、发现/绑定、显隐、绘制、诊断与释放等职责组织。一个流程的相关函数尽量相邻，避免入口夹在反射辅助函数之间。分组注释只用于长类中的职责边界，不给每个函数重复添加说明。

函数名使用能体现动作和对象的词，结合所在类判断，不机械增加前缀：

| 语义       | 命名方式与例子                                                                 |
| ---------- | ------------------------------------------------------------------------------ |
| 事件入口   | `onTextWritten`、Android/框架规定的回调名保持原样                              |
| 布尔判断   | `isCurrentDocument`、`hasOwnedMarker`，表达实际判断对象                        |
| 查找与创建 | `findNativeSession` 可返回空；`findOrCreateBalanceSession` 明确可能创建        |
| 读取与呈现 | `readScriptExpression`、`getAmountText`、`renderConfiguration`，区分数据与界面 |
| 异步任务   | `requestConfigurationRead`、`scheduleInspectionRetry`，说明请求或调度          |
| 资源释放   | `close`、`releaseField`、`retireDocument`，延续对应所有权语义                  |

SDK 反射名称、Android 回调、配置键、脚本命令/返回字段及持久标记属于协议；重命名本地函数时不能连带改变它们。修改函数引用时注意局部同名变量、对象属性及 JavaScript 对象简写。

注入脚本在同一 IIFE 中按命令生命周期、协调与观察、绑定/归属/显隐、DOM 识别、纯工具函数排列。只有可提升的函数声明可以按职责移动；初始化、早退和其他执行语句的原顺序必须保留。

大规模移动应先保存可比较的源码基线，并核对方法体、字段初始化顺序、协议字面量和注册顺序。提取后的纯规则不依赖 Android 对象；仅函数重排不构成修改状态机或超时规则的理由。

## 包名迁移检查

当前应用 ID、Java 命名空间和模块入口统一使用 `io.github.fengying.alipaydisplay`。包名调整属于应用身份变更，应同步检查源码与测试目录、模块入口配置、Web 资源路径、私有文档标识及桥 DEX 的生成和加载路径；宿主应用及 SDK 符号保持原样。

本地验证应核对最终 APK 的应用 ID、设置页和模块入口，确认打包资源与桥接类位于新命名空间，并检查是否残留旧生成产物。历史设备验证不能自动视为新包名的实机验收；未执行的安装、配置迁移和设备检查需明确报告。

新包名不会自动继承旧包名的草稿或 LSPosed 配置。不要把卸载旧应用、复制私有配置或安装到设备混入源码迁移；使用者操作见 [包名迁移说明](docs/usage.md#从旧包名迁移)。

## 改动边界与验证

显示配置只作用于已识别的显示文本、绘制副本和朗读描述。不要改变账户数据、网络响应、支付参数或共享业务模型；不要扩大到收益、利率、基金明细等非目标字段。

页面适配必须同时说明已验证的宿主/资源版本、唯一归属和失败行为。未知结构保持宿主原样；隐藏优先，不使用隐藏前的金额缓存恢复显示。检查后台返回、配置更新与页面销毁后，旧回调不能应用到新对象。

测试使用合成数据。优先验证可观察的边界：未知版本、隐藏恢复、重复回调、过时代次、原对象隔离和清理行为。不要通过弱化断言或扩大匹配范围获得通过。

界面改动还需检查深浅色、小屏、大字体、键盘遮挡、控件标签和保存失败反馈。涉及宿主适配时，单元测试和构建通过不能替代实机验证；在 PR 中区分通过、失败、未运行和仅只读核对的项目。

## Issue 与 Pull Request

问题报告应包含版本、复现步骤、预期与实际行为。只提供必要且脱敏的诊断分类，不上传真实金额、账户、基金名称、URL 查询参数或完整界面转储；能用合成示例说明的问题不附原始截图。

PR 应聚焦一个目标，说明行为变化、影响范围、验证结果及剩余限制。同步更新受影响的文档和测试，检查最终差异，保留其他贡献者的工作。

提交信息使用 Conventional Commits：小写英文类型，可选作用域，简体中文说明且无句末标点。例如 `fix(settings): 保留未保存的金额草稿`。提交前只暂存本次改动。

贡献的代码按仓库 [MIT 许可证](LICENSE) 提供。第三方代码仍需保留其原有许可和版权声明。
