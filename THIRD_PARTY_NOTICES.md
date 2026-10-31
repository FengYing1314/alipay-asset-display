# 第三方声明

MIT 许可证适用于本仓库贡献者有权授权的项目代码。下列直接声明的依赖、构建组件和开发工具保留各自的版权及许可证，不因本项目采用 MIT 而改变。

| 组件                                                                  | 固定版本 | 用途                              | 许可证     |
| --------------------------------------------------------------------- | -------- | --------------------------------- | ---------- |
| [libxposed API](https://github.com/libxposed/api)                     | 102.0.0  | 编译期 API，不随 APK 打包         | Apache-2.0 |
| [libxposed service / interface](https://github.com/libxposed/service) | 102.0.0  | 框架连接与远程配置，随 APK 打包   | Apache-2.0 |
| [JUnit](https://github.com/junit-team/junit4)                         | 4.13.2   | Java 测试，不随 APK 打包          | EPL-1.0    |
| [Gradle Wrapper](https://github.com/gradle/gradle)                    | 9.6.0    | 构建启动脚本和 Wrapper            | Apache-2.0 |
| [google-java-format](https://github.com/google/google-java-format)    | 1.37.0   | Java 格式工具，不随 APK 打包      | Apache-2.0 |
| [Prettier](https://github.com/prettier/prettier)                      | 3.9.6    | 脚本和文档格式工具，不随 APK 打包 | MIT        |

项目自身的 [MIT 许可文本](app/src/main/resources/META-INF/LICENSE-alipaydisplay.txt) 和随 APK 提供的 libxposed 组件对应的 [Apache-2.0 许可文本](app/src/main/resources/META-INF/LICENSE-libxposed.txt) 作为模块资源一并打包。JUnit 与开发工具的完整声明由各自依赖包提供；新增或变更依赖时需同步核对其许可。

本表记录项目直接使用的组件，不代替正式发行产物的完整依赖清单。宿主应用和 Android 平台不随本项目分发；用于编译的 SDK 回调桩只声明接口形状，不包含宿主实现。
