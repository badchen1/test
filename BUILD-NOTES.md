# 构建记录与复现指南

本文档记录了本工程**实际构建出 APK 的完整过程**，包括环境版本、每一步命令，以及踩过的坑。
按本文操作即可在另一台 Windows 机器上复现出同样的 APK。

---

## 1. 构建结果

| 项目 | 值 |
|---|---|
| 产物 | `dist/KugoMusicConverter-1.0.0-debug.apk` |
| 大小 | 68 MB |
| 包名 | `com.kugo.mobile.debug`（Debug 变体带 `.debug` 后缀） |
| versionName / versionCode | `1.0.0` / `1` |
| minSdk / targetSdk / compileSdk | 24 / 35 / 35 |
| 覆盖 ABI | `arm64-v8a`、`armeabi-v7a`、`x86_64`（通用包） |
| 签名 | Android Debug 证书，可直接 `adb install` 侧载 |
| Go 核心 | `mobile/app/libs/gocore.aar`（21 MB，含三套 `libgojni.so`） |

> Debug 包用系统调试证书签名，**不能上架应用商店**。要出正式包，需在
> `mobile/app/build.gradle.kts` 里补 `signingConfigs`，再跑 `assembleRelease`。

---

## 2. 构建环境（实测版本）

| 组件 | 版本 | 用途 |
|---|---|---|
| Go | 1.26.6 | 编译解密核心（`go.mod` 要求 ≥ 1.26.6） |
| gomobile / gobind | `golang.org/x/mobile` 最新版 | 生成 `.aar` |
| JDK | Microsoft OpenJDK 17.0.13 | Android Gradle Plugin 8.x 要求 |
| Android SDK Platform | android-35 | compileSdk 35 |
| Android Build-Tools | 35.0.0 | aapt2 / d8 / apksigner |
| Android NDK | 26.3.11579264（r26d） | gomobile 交叉编译 |
| Gradle | 8.11.1 | 与 `gradle-wrapper.properties` 一致 |
| AGP / Kotlin | 8.7.3 / 2.0.21 | 见 `mobile/build.gradle.kts` |

环境变量（`setenv` 一次即可）：

> **本次构建用的工具链已保留在本机**，可直接复用：
>
> | 位置 | 内容 | 大小 |
> |---|---|---|
> | `D:\androidbuild\sdk` | Android SDK 35 / Build-Tools 35.0.0 / NDK r26d / platform-tools | 2.6 GB |
> | `D:\androidbuild\jdk17` | Microsoft OpenJDK 17.0.13 | 303 MB |
> | `D:\androidbuild\gradle-8.11.1` | Gradle 发行包 | 146 MB |
> | `%USERPROFILE%\androidbuild\go` | Go 1.26.6（GOROOT） | 256 MB |
> | `%USERPROFILE%\androidbuild\gopath` | Go 模块缓存 + `gomobile` / `gobind` | 593 MB |
> | `%USERPROFILE%\.gradle` | Gradle 依赖缓存 | 1.4 GB |
>
> 现成的环境变量脚本：**`bash D:\androidbuild\env.sh`**（或直接 `source`）。
> 一次性下载包与临时副本已清理。
>
> 注意：Go 工具链与 GOPATH 必须留在 **C 盘** —— 实测 D 盘上 Go 的文件锁会失败。

```bash
export ANDROID_HOME='D:\androidbuild\sdk'
export ANDROID_NDK_HOME="$ANDROID_HOME\ndk\26.3.11579264"
export JAVA_HOME='D:\androidbuild\jdk17'
export GOROOT='C:\Users\<你>\androidbuild\go'
export GOPATH='C:\Users\<你>\androidbuild\gopath'
export GRADLE_USER_HOME='C:\Users\<你>\.gradle'
export GOPROXY='https://goproxy.cn,direct'
export PATH="$JAVA_HOME/bin:$GOROOT/bin:$GOPATH/bin:$PATH"

# ⚠️ 必需：中文 Windows 下 javac 默认按 GBK 解码，
#    而 gomobile 生成的 .java 含 UTF-8 中文注释，不加这行会编译失败。
export JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

---

## 3. 复现步骤

### 第一步：校验核心与绑定

```bash
cd KugoMobile
./verify-bindings.sh
```

四层校验全部通过：Go 编译 → gomobile 绑定可行性 → 13 个 Kotlin 调用与生成 API 一致 → 6 个跨语言错误码一致。

### 第二步：编译 Go 核心为 .aar

```bash
./build-gocore.sh all      # 三 ABI 通用包；只要真机可写 ./build-gocore.sh arm64
```

产物：`mobile/app/libs/gocore.aar`。
**首次编译很慢**（要为本机 + 每个目标 ABI 分别编译 `modernc.org/sqlite` 等依赖）。
若中途被中断，直接重跑即可——Go 构建缓存会接着用，不会从头再来。

### 第三步：打包 APK

```bash
cd mobile
./gradlew assembleDebug
```

产物：`mobile/app/build/outputs/apk/debug/app-debug.apk`。

> 直接 `./gradlew` 会去 `services.gradle.org` 下载 Gradle 发行包，本机不通。
> 用本机已装的那份更快：
> ```bash
> /d/androidbuild/gradle-8.11.1/bin/gradle assembleDebug
> ```

如果 `dl.google.com` / `repo.maven.apache.org` 访问不稳，加国内镜像（见下节）：

```bash
gradle --init-script mirrors.init.gradle assembleDebug
```

---

## 4. 国内网络下的依赖镜像

本机实测：官方源 `dl.google.com`（AGP、Android 依赖）与 `repo.maven.apache.org`
连接极不稳定，而下列镜像稳定高速：

| 用途 | 镜像 |
|---|---|
| Maven（google / central / gradle-plugin） | `https://maven.aliyun.com/repository/{google,central,gradle-plugin}` |
| Android SDK / NDK / Build-Tools | `https://mirrors.cloud.tencent.com/AndroidSDK/` |
| Gradle 发行包 | `https://mirrors.cloud.tencent.com/gradle/` |
| Go | `https://mirror.nju.edu.cn/golang/`（实测 14 MB/s）或 `https://mirrors.aliyun.com/golang/` |
| Go 模块代理 | `https://goproxy.cn` |

工程内提供了 `mobile/mirrors.init.gradle`，**只在显式指定时生效，不改变默认行为**：

```bash
gradle --init-script mirrors.init.gradle assembleDebug
```

如果下载仍慢，可把 `mobile/gradle/wrapper/gradle-wrapper.properties` 里的
`distributionUrl` 换成腾讯镜像：

```
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip
```

---

## 5. 踩过的坑（重要）

### 5.1 工程路径含中文会被 AGP 直接拒绝

AGP 默认校验工程路径，遇到 `D:\中文目录\KugoMobile` 这类非 ASCII 路径会直接报
`Your project path contains non-ASCII characters`。

本工程已在 `mobile/gradle.properties` 中打开开关：

```properties
android.overridePathCheck=true
```

实测在中文路径下 `assembleDebug` 可以正常出包。若将来升级 AGP 后出现资源或打包异常，
最稳妥的做法是把工程放到全 ASCII 路径（如 `C:\dev\KugoMobile`）再构建。

### 5.2 javac 用 GBK 解码 gomobile 生成的 Java

`gomobile bind` 会把 Go 的文档注释原样写进生成的 `.java`，其中有中文。
中文 Windows 上 `javac` 默认按 GBK 解码，直接报 `编码 GBK 的不可映射字符`，绑定失败。

解决办法就是环境变量（已写入上面的 `setenv`）：

```bash
export JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

### 5.3 NDK 版本警告（可忽略）

只有 NDK r26d 时，AGP 会打印：

```
[CXX1104] NDK from ndk.dir ... had version [26.3.11579264]
          which disagrees with android.ndkVersion [27.0.12077973]
```

这是 AGP 8.7 的默认 NDK 版本（27）与 `local.properties` 里指定的 26d 不一致导致的**警告**，
不影响构建。本项目没有 CMake/native 构建，NDK 只被用来 strip 符号。

---

## 6. 本次修复的源码问题

原始工程并未真正编译过 Kotlin 代码，构建时暴露出 4 处问题，均已修复：

| 文件 | 问题 | 修复 |
|---|---|---|
| `MainActivity.kt` | `"mediaMid$ekey"`、`"音频哈希$密钥"` 中 `$` 未转义，被 Kotlin 当作字符串模板 | 改为 `\$ekey`、`\$密钥` |
| `MainActivity.kt` | 顶部 `KugoApp()` 是顶层 Composable，`contentResolver` 不在作用域内（它属于 Context） | 加 `val context = LocalContext.current`，改用 `context.contentResolver` |
| `MainActivity.kt` | 用了 `Modifier.fillMaxWidth()` 但未 import | 补 `import androidx.compose.foundation.layout.fillMaxWidth` |
| `ui/SettingsScreen.kt` | 同样两处 `$密钥` / `$ekey` 未转义 | 改为 `\$密钥`、`\$ekey` |

另外补齐了原压缩包里缺失的 **Gradle Wrapper**（`gradlew`、`gradlew.bat`、`gradle-wrapper.jar`），
现在可以直接用 `./gradlew` 构建。

---

## 7. 后续开发建议

- **只改 Kotlin / UI**：直接在 `mobile/` 下改，然后 `./gradlew assembleDebug`。
  `gocore.aar` 已经放在 `mobile/app/libs/`，不需要 Go 工具链。
- **改了 Go 核心**：重新跑 `./build-gocore.sh`，然后 `./verify-bindings.sh` 确认
  Kotlin 调用与错误码没跑偏，再重新打包。
- **调试真机**：`adb install -r dist/KugoMusicConverter-1.0.0-debug.apk`。
  因为是通用包，模拟器（x86_64）同样能装。
- **减小体积**：通用包 68 MB 主要是三份 `libgojni.so`（各约 18 MB）。
  如果只面向真机，用 `./build-gocore.sh arm64` 重新出 aar，包体可降到约 23 MB。
- **正式发布**：补 `signingConfigs` 后用 `assembleRelease`，并建议开启
  `isMinifyEnabled` 与 `isShrinkResources`。
