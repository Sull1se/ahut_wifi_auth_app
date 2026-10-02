# 第三方组件

本项目的 MIT 许可证适用于项目原创内容，不替换第三方组件的许可证。保留源码和工具中已有的版权与许可声明。

- **Gradle Wrapper**：`gradlew`、`gradlew.bat` 与 `gradle/wrapper/gradle-wrapper.jar`，Apache License 2.0。许可全文见 [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt)；上游项目为 [Gradle](https://github.com/gradle/gradle)。Wrapper JAR SHA-256 为 `381dff8aa434499aa93bc25572b049c8c586a67faff2c02f375e4f23e17e49de`，与 [Gradle 官方校验表](https://gradle.org/release-checksums/) 中的 4.6 Wrapper 相符；它按项目属性文件启动 Gradle 9.1.0。
- **Android Gradle Plugin、AndroidX、Kotlin 及其传递依赖**：构建时从 Google Maven / Maven Central 下载，版本声明见 `gradle/libs.versions.toml`，遵循各自发布包中的许可声明。依赖缓存没有随源码仓库分发。
- **JUnit 与 org.json 测试依赖**：仅用于 JVM 单元测试，不作为源码文件复制进仓库；许可信息随上游依赖包提供。

启动器资源沿用项目已有的 Android Studio 模板资源。Android 名称及相关标识的使用受其各自的品牌规则约束。
