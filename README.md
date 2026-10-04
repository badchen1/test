# 音乐解密转换 · Android 版

把 [Kugo-Music-Converter](https://github.com/skxxxkx666/Kugo-Music-Converter) 的桌面程序移植为 Android 应用。

支持解密**酷狗 / 网易云 / 酷我 / QQ音乐**的加密音频，输出为原始未加密音频文件。

---

## 当前状态

| 模块 | 状态 | 说明 |
|---|---|---|
| Go 解密核心 | ✅ 可编译、测试通过 | 8 项测试全绿，含 NCM 端到端往返验证 |
| Android arm64 交叉编译 | ✅ 通过 | 关键依赖 `modernc.org/sqlite` 在 Android 可用 |
| gomobile 绑定 | ✅ 校验通过 | 13 个 API 全部可绑定，签名合规 |
| Kotlin 工程 | ✅ 已编译通过 | Compose UI、SAF 文件访问、密钥持久化 |
| 错误码跨语言一致性 | ✅ 自动校验 | 见 `verify-bindings.sh` |
| **APK 打包** | ✅ **已完成** | 通用包（arm64-v8a / armeabi-v7a / x86_64），见 `dist/` |

> 本工程已在装有 Android SDK 35 / NDK r26d / JDK 17 / Go 1.26.6 的机器上完整跑通，
> 产物为 `dist/KugoMusicConverter-1.0.0-debug.apk`。构建环境、命令与注意事项见 [`BUILD-NOTES.md`](BUILD-NOTES.md)。


---

## 快速开始

### 前置环境

| 依赖 | 版本要求 | 说明 |
|---|---|---|
| **Go** | 1.21+ | 编译解密核心 |
| **gomobile** | latest | `go install golang.org/x/mobile/cmd/gomobile@latest` |
| **Android Studio** | Ladybug (2024.2) 或更新 | 含 Android SDK 35 |
| **NDK** | r26 或更新 | gomobile 编译用 |
| **JDK** | 17 | Android Gradle Plugin 8.x 要求 |

安装 gomobile：

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
```

确认 `$(go env GOPATH)/bin` 在 `PATH` 中。

### 第一步：校验核心

```bash
cd KugoMobile
./verify-bindings.sh
```

预期输出四层校验全部通过。若失败，按提示修复后再继续。

### 第二步：编译 Go 核心为 .aar

```bash
export ANDROID_HOME=$HOME/Android/Sdk    # 改成你的 SDK 路径
./build-gocore.sh
```

产物：`mobile/app/libs/gocore.aar`。

只编单一 ABI 可加快速度：

```bash
./build-gocore.sh arm64     # 真机调试通常够用
```

### 第三步：构建 APK

用 Android Studio 打开 `mobile/` 目录，等待 Gradle 同步完成后：

- **调试包**：`Build > Build Bundle(s) / APK(s) > Build APK(s)`
- **命令行**：
  ```bash
  cd mobile
  ./gradlew assembleDebug      # 产物在 app/build/outputs/apk/debug/
  ./gradlew assembleRelease    # 需先配置签名
  ```

首次构建会下载 Gradle 与依赖，耗时较久。

---

## 使用说明

### 基本流程

1. 点右下角 **+** 选择加密音频（可多选）
2. 点 **开始解密**，等待完成
3. 产物默认存到应用私有目录；也可在设置里指定输出目录

### 密钥导入

不同格式对密钥的要求不同：

| 格式 | 是否需要密钥 | 导入方式 |
|---|---|---|
| `.ncm` `.kwm` `.kgm` `.kgma` `.vpr` `.qmc0~qmc8` `.qmcflac` `.qmcogg` `.tkm` | 不需要 | 直接解密 |
| `.kgg` | **需要酷狗密钥** | 设置页导入 `kgg.key` 或 `KGMusicV3.db`，也可粘贴文本 |
| `.mflac` `.mgg` | **需要 QQ音乐 ekey** | 设置页粘贴 ekey 文本 |

### 关于 QQ音乐 ekey

QQ音乐**没有开放第三方登录接口**。桌面版是通过读取本机 QQ音乐客户端的凭证（配置文件 + 进程内存扫描）来获取 ekey 的，Android 上不存在这个条件。

因此 Android 版采用**手动导入**方案：

1. 在可信环境（如装了原桌面版的电脑）中导出目标文件的 ekey
2. 在 App 设置页粘贴，格式为每行一条：
   ```
   mediaMid$ekey
   ```
   也可用文件名做兜底匹配：
   ```
   歌曲名$ekey
   ```

> **为什么不做 App 内自动登录？** 官方无公开接口，任何"自动登录"都需要用户输入 QQ 账号密码，存在账号安全风险，且官方风控策略随时可能变更导致功能失效。手动导入虽然多一步，但稳定可靠。

### 密钥的保存

导入的密钥保存在应用私有目录（`/data/data/com.kugo.mobile/files/keys/`），其他应用无法读取。重启 App 会自动恢复，无需重复导入。

---

## 项目结构

```
KugoMobile/
├── gocore/                        Go 解密核心（编译为 .aar）
│   ├── decryptor.go               gomobile 门面：解密、密钥、探针
│   ├── log.go                     静默 logger
│   ├── decryptor_test.go          端到端测试
│   ├── internal/algo/
│   │   ├── kgg/                   酷狗 KGG / QMC2 / ekey / DB 解密
│   │   └── qmcfile/               QQ音乐 musicex 容器
│   └── third_party/mmkv/          unlock-music 依赖的桩实现
│
├── mobile/                        Android 工程
│   └── app/src/main/java/com/kugo/mobile/
│       ├── MainActivity.kt        入口、SAF 选择器、导航
│       ├── core/
│       │   ├── DecryptEngine.kt   Go 核心的 Kotlin 门面
│       │   └── JsonMini.kt        零依赖 JSON 解析
│       ├── data/
│       │   ├── FileBridge.kt      URI ↔ 真实路径桥接
│       │   └── KeyStore.kt        密钥持久化
│       └── ui/
│           ├── MainScreen.kt      主界面
│           ├── SettingsScreen.kt  设置页
│           ├── TaskCard.kt        任务卡片 + 波形动画
│           ├── Components.kt      通用组件
│           ├── MainViewModel.kt   状态管理
│           └── Theme.kt           配色
│
├── build-gocore.sh                编译 .aar
└── verify-bindings.sh             跨语言一致性校验
```

---

## 架构说明

```
┌─────────────────────────────────────────────┐
│  Kotlin / Jetpack Compose UI                │
│  MainScreen · SettingsScreen · TaskCard     │
└──────────────────┬──────────────────────────┘
                   │ StateFlow
┌──────────────────▼──────────────────────────┐
│  MainViewModel（状态管理、串行调度）           │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│  DecryptEngine（错误码翻译、lambda 包装）      │
│  FileBridge（SAF ↔ 路径）· KeyStore（持久化）  │
└──────────────────┬──────────────────────────┘
                   │ JNI（gomobile 生成）
┌──────────────────▼──────────────────────────┐
│  Go 核心 (gocore.aar)                        │
│  DecryptFile · SetKGGKeyFile · …             │
├─────────────────────────────────────────────┤
│  internal/algo/kgg      酷狗解密算法          │
│  internal/algo/qmcfile  QQ音乐容器解析        │
│  unlock-music.dev/cli   NCM / KWM / QMC 解码 │
└─────────────────────────────────────────────┘
```

### 几个关键设计决策

**串行解密而非并发。** 解密是纯 CPU 密集型任务，并发只会争抢资源、让进度回调互相干扰。串行处理在体验上反而更稳定。

**文件先落地到缓存再解密。** Go 的 KGG / QMC 解密需要多次 `Seek` 读头部，SAF 的 `ParcelFileDescriptor` 在部分实现上不支持随机定位。先拷贝到缓存目录拿到真实路径最稳妥，任务结束立即清理。

**错误码用 int32 跨语言传递。** gomobile 无法传递 Go 的 `error`，且只允许返回 `(T, error)` 两个值。因此用结构体 `DecryptResult` 打包结果，配 `verify-bindings.sh` 自动校验 Go 与 Kotlin 的错误码一致。

**密钥持久化在私有目录，不做额外加密。** 密钥本身就是对称密钥材料，再套一层固定密钥的本地加密只是"混淆"，反而给人虚假的安全感。真正的边界是 Android 应用沙箱。

---

## 开发

### 运行测试

```bash
cd gocore
go test -v ./...
```

测试包含一个完整的 NCM 往返验证：构造合法 NCM 文件 → 解密 → 校验字节完全一致。

### 修改后的验证流程

改动 Go 门面或 Kotlin 调用后，务必跑：

```bash
./verify-bindings.sh
```

它会检查：Go 编译、gomobile 绑定可行性、Kotlin 调用与生成 API 的一致性、跨语言错误码一致性。

### gomobile 的签名限制

写 Go 门面时注意：

- 函数最多返回 `(T, error)` 两个值
- 不支持 `map`、`interface{}`、可变参数
- 不支持返回切片（用 JSON 字符串替代，如 `SupportedFormats`）
- 回调只支持单方法接口（如 `Progress`）

---

## 与桌面版的差异

| 功能 | 桌面版 | Android 版 |
|---|---|---|
| 解密算法 | 完整 | **完全一致**（同一份 Go 代码） |
| 转码（MP3/FLAC/WAV） | 支持（内嵌 FFmpeg） | **不支持**，只输出解密后的原始音频 |
| QQ音乐密钥获取 | 自动（读本机客户端） | **手动导入 ekey** |
| 酷狗密钥发现 | 自动扫描 `tools/` 目录 | **手动导入** |
| 文件选择 | 拖拽 / 目录 | 系统文件选择器（可多选） |
| 输出格式 | 可选 MP3/FLAC/WAV | 按源文件推断 |

### 关于转码

当前版本**只做解密，不做转码**。

解密后的产物已经是可直接播放的标准音频（FLAC / MP3 / OGG），绝大多数场景够用。若确实需要转码，后续可集成 ffmpeg-kit，代价是 APK 体积增加 40–80MB。

---

## 已知限制

1. **`.kgg` 需要密钥**，App 无法自动发现。需从桌面版环境导出后导入。
2. **`.mflac` / `.mgg` 需要 ekey**，同上。
3. **STag 容器不支持**。这是 QQ音乐较新的容器变体，原桌面版也不支持。
4. **不做转码**，输出为解密后的原始格式。
5. **大文件内存占用**：解密是流式的，内存占用与文件大小无关，但缓存目录会占用等同源文件的空间。

---

## 故障排查

| 现象 | 原因与处理 |
|---|---|
| 构建报错找不到 `gocore.aar` | 先跑 `./build-gocore.sh` |
| `gomobile: command not found` | 把 `$(go env GOPATH)/bin` 加入 `PATH` |
| 构建报 NDK 找不到 | 在 `local.properties` 里写 `ndk.dir=...`，或用 Android Studio 的 SDK Manager 装 NDK |
| `.kgg` 解密报"缺少密钥" | 设置页导入 `kgg.key` 或 `KGMusicV3.db` |
| `.mflac` 解密报"ekey 无效" | ekey 过期，需重新导出 |
| 解密出的文件无法播放 | 检查源文件是否完整；部分平台文件下载不全会导致此问题 |

---

## 许可

本项目是对 [Kugo-Music-Converter](https://github.com/skxxxkx666/Kugo-Music-Converter) 的移植。

解密算法依赖 [unlock-music.dev/cli](https://git.unlock-music.dev/um/cli)（MIT）。

**请仅用于解密你自己合法获取的音乐文件。**
