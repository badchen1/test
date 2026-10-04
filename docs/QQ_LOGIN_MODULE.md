# QQ 登录模块（WebView 内嵌登录 + Cookie 抓取）

> 面向 `KugoMobile`（酷狗音乐转换 · Android）的账号接入模块。
> 目标：**不引入任何第三方 SDK**，用系统 WebView 打开 QQ 官方网页登录页，
> 抓取登录 Cookie 并持久化，供 Go 解密核心向 QQ 音乐换取 ekey。

---

## 0. 整体实现思路

### 为什么走「WebView + Cookie」而不是官方 SDK

| 方案 | 结论 |
|---|---|
| QQ互联 SDK（`tencent-open-sdk` / `TecentOpenSDK`） | 需要企业资质申请 appid、签名校验、审核周期长；且目标是 QQ **音乐**的 ekey，SDK 只给 openid/access_token，拿不到音乐密钥 |
| 唤起手机 QQ 客户端授权 | 依赖用户装了 QQ，且仍然只返回 openid；回到应用后还要再走一次 Web 流程 |
| **应用内 WebView 打开 `ptlogin2` 免密登录页 → 抓 Cookie** | ✅ 采用。链路最短，拿到的 `qqmusic_key` 就是站点自己的登录票据，可直接用于换取 ekey |

### 数据流

```
用户点击「登录 QQ 账号」
   │
   ▼
QQLoginActivity（独立窗口）
   │  加载 https://xui.ptlogin2.qq.com/... （s_url → y.qq.com/portal/profile.html）
   ▼
用户在官方登录页输入账号密码 / 完成验证
   │
   ▼
ptlogin2 302 → s_url，同时 y.qq.com 下发 qqmusic_key
   │
   ▼
onPageFinished / shouldOverrideUrlLoading 触发 → CookieManager 读取
   │  （递增延时重试 0/400/1200/2500/4000ms）
   ▼
QQCookieExtractor 抽字段 → QQSession
   │
   ├─▶ QQSessionStore   Keystore(AES-256-GCM) 加密落盘
   ├─▶ QQWebCookieJar.flush()   内存 Cookie 落盘
   └─▶ Gocore.setQMCCookie()    同步给 Go 核心 → 自动换 ekey
   │
   ▼
musicu.fcg 在线校验 → 有效则 setResult(RESULT_OK) 回主界面
```

### 分层

```
com.kugo.mobile.auth
├── QQAuthConfig            常量：URL / appid / Cookie 字段名 / 超时 / 风控域名
├── QQAuthResult            终态与错误码（sealed interface）
├── QQLoginWebViewFactory   WebView 构造与加固（选型落地在这）
├── QQWebCookieJar          CookieManager / WebStorage 门面
├── QQCookieExtractor       Cookie 解析、合并、脱敏（纯函数）
├── QQSession               会话数据模型（含过期推断）
├── QQSessionStore          AndroidKeyStore 加密持久化
├── QQSessionValidator      在线 / 本地有效性校验
├── QQSessionManager        登录态唯一持有者（单例）
├── QQLoginViewModel        登录流程状态机
└── QQLoginActivity         容器 Activity

com.kugo.mobile.ui
└── QQLoginScreen           Compose UI + WebView 宿主 + 回调转发
```

**设计原则**：ViewModel 不持有 WebView，只接收回调、产出状态。这样横竖屏切换、
Activity 重建都不会牵连 WebView 生命周期，也杜绝了最常见的内存泄漏。

---

## 1. WebView 选型与初始化

### 选型依据

1. **系统 WebView（Chrome / Blink）** —— 唯一可行选项。
   `ptlogin2` 依赖最新的 TLS 指纹与 ES2020 语法；系统 WebView 随 Chrome 一起升级，
   兼容性与真实浏览器完全一致，且不增加 APK 体积。
2. **腾讯 X5 / TBS** —— 官方已停止对第三方应用的独立分发，需商业授权，排除。
3. **GeckoView** —— 体积 +60MB，登录页针对 Blink 优化，性价比低。
4. **Custom Tabs / 系统浏览器** —— 运行在**独立进程**，宿主无法读取其 Cookie，
   与「抓 Cookie」的核心需求直接冲突，排除。

### 关键配置项（`QQLoginWebViewFactory`）

| 配置 | 值 | 理由 |
|---|---|---|
| `javaScriptEnabled` | `true` | ptlogin2 是纯 JS 应用，关掉直接白屏 |
| `domStorageEnabled` | `true` | 登录页用 `sessionStorage` 记录流程状态 |
| `databaseEnabled` | `true` | 部分风控组件依赖 IndexedDB |
| `userAgentString` | 系统 Chrome UA **剥掉 `; wv)` 与 `Version/4.0`** | 命中 `wv` 会被判定为「嵌入式浏览器」，风控等级更高；命中 `MicroMessenger` / `QQ/` 会被导向「唤起 App」分支 |
| `setAcceptCookie` | `true` | 登录必需 |
| `setAcceptThirdPartyCookies` | `true` | ⚠️ **最关键的一项**。登录结果由跨域 iframe 写回，关掉会出现「授权页显示成功、站点拿不到 key」 |
| `setSupportMultipleWindows` | `false` | 否则 `onCreateWindow` 会弹出不受控的新窗口，登录结果写在另一个 WebView 上 |
| `cacheMode` | `LOAD_NO_CACHE` | 陈旧的 ptlogin 页面会拿过期票据换 key，表现为「密码明明对了却报参数错误」 |
| `allowFileAccess` / `allowContentAccess` / `*FromFileURLs` | `false` | 登录页不需要任何本地文件能力，收紧攻击面 |
| `mixedContentMode` | `NEVER_ALLOW` | 防降级 |
| `textZoom` | `100` | 系统字号调大时登录页会被挤变形 |
| `geolocationEnabled` / `saveFormData` | `false` | 不需要，且避免把账号密码留在磁盘 |
| `setWebContentsDebuggingEnabled` | 仅 Debug 包 | 可用 `chrome://inspect` 直接调试登录页 |

**未使用 `addJavascriptInterface`**：注入 Java 对象到登录页等于把攻击面交给第三方页面。
所有结果判定都走 `WebViewClient` 回调与 `CookieManager` 读取。

---

## 2. 登录流程

### 入口 URL

```
https://xui.ptlogin2.qq.com/cgi-bin/xlogin
  ?appid=716027609          # QQ互联「网页登录」固定 appid，不是本应用的 appid
  &daid=383&style=33&hide_title_bar=1&hide_border=1&target=self
  &s_url=<urlencode(https://y.qq.com/portal/profile.html)>   # 登录成功后的落点
  &pt_3rd_aid=100497308     # QQ音乐在 QQ互联 侧的第三方应用 id
```

- `s_url` 必须指向**目标站点自己的页面**，否则拿不到站点的登录 Cookie。
- 主入口失败时自动切换到 `graph.qq.com/oauth2.0/show?which=Login`（版本升级兜底）。

### 结果判定机制

| 信号 | 判定 |
|---|---|
| `shouldOverrideUrlLoading` 命中 `y.qq.com` | 触发一次补抓（**不拦截**跳转，拦截会破坏票据传递） |
| `onPageFinished` | 主抓取时机 |
| `CookieManager` 出现 `qqmusic_key` / `qm_keyst` | **成功** |
| `onPageFinished` 且 URL 回到首页但无 key | 不判失败，继续等待用户操作 |
| 用户按返回键 / 点「取消」 | **取消**（`LoginOutcome.UserCancelled`） |
| 超过 5 分钟无结果 | **超时**（`Timeout`） |
| 主文档加载错误码 ∈ {HOST_LOOKUP, CONNECT, TIMEOUT, IO} | **网络异常**（`NetworkError`） |
| SSL 证书错误 | **失败**，`handler.cancel()`，不放行 |

**回调机制**：登录页是独立 Activity，结果通过 `setResult` + `EXTRA_OK/EXTRA_UIN/EXTRA_CODE/EXTRA_MESSAGE`
回传，调用方不需要依赖 `auth` 包的内部类型。

---

## 3. Cookie 抓取与存储

### 抓取时机

页面跳转完成后**不是**抓一次就够。登录成功的 `Set-Cookie` 由 ptlogin2 的
**跨域 iframe** 或站点 XHR 写回，`onPageFinished` 触发时它往往还没落进 `CookieManager`。

因此采用**递增延时重试**：`0ms → 400ms → 1200ms → 2500ms → 4000ms`，
任一次抓到即收敛；全部落空则退回「等待用户操作」，等下一次页面事件再试。

### 需要的 Cookie 字段

| 字段 | 必需 | 用途 |
|---|---|---|
| `qqmusic_key` / `qm_keyst` | ✅ | QQ 音乐登录票据，换 ekey 的核心凭据 |
| `uin` / `qqmusic_uin` / `wxuin` | 展示 | QQ 号 |
| `skey` / `p_skey` | 续期 | 免密登录（静默续期）依赖它 |
| `psrf_qqunionid` / `psrf_qqopenid` | 备用 | 账号唯一标识 |
| `psrf_access_token_expiresAt` | 过期推断 | QQ互联 access token 到期时间戳 |
| `psrf_musickey_createtime` | 过期推断 | 音乐密钥签发时间 |
| `psrf_qqrefresh_token` / `wxrefresh_token` | 备用 | 未来接 OAuth 时使用 |

### 多域名合并

Cookie 按 domain 隔离，`qqmusic_key` 可能只写在 `.y.qq.com`，而 `skey` 写在 `.qq.com`。
`CookieManager.getCookie(url)` 只返回 domain 能匹配该 url 的 Cookie，
因此按 `COOKIE_URLS`（`qq.com → graph.qq.com → y.qq.com → u.qq.com → c.qq.com → i.qq.com`）
**逐个查询后合并**，越具体的域越靠后、优先级越高。

### 持久化

| 层 | 位置 | 形式 |
|---|---|---|
| 落盘会话 | `/data/data/<pkg>/files/keys/qq_session.bin` | **AndroidKeyStore AES-256-GCM 加密**，格式 `[版本1B][IV长度1B][IV][密文+Tag]` |
| 内存 Cookie | `CookieManager` | 抓到后立即 `flush()` 落盘，防进程被杀丢失 |
| Go 核心 | 进程内存 | `Gocore.setQMCCookie(cookieHeader)` —— 冷启动必须重新注入 |

**为什么这里加密而 `kgg.txt` 用明文**：敏感级别不同。ekey 只能解密已经躺在用户
设备上的某个文件；QQ Cookie 等价于账号通行证，拿到就能以用户身份调用全部接口。
设备被 root 或应用目录被备份带走时，明文存储的后果完全不同。

密钥材料永不离开 TEE，应用只能请求它做加解密、无法导出，文件被拷走也解不开。
极端机型（部分模拟器）Keystore 不可用时降级为明文并标记，功能不中断。

### 过期清理策略

```
expiresAt = min(
    psrf_musickey_createtime + 24h,      # 音乐密钥自身生命周期
    psrf_access_token_expiresAt,         # QQ互联 token 到期
    抓取时刻 + 24h                        # 兜底
)
（只保留 > now 的候选；全部无效则用兜底值）
```

- 时间戳自动识别秒 / 毫秒（数量级判定）。
- 剩余有效期 ≤ 1 小时进入「建议续期」窗口。
- 冷启动加载时若已过期 → 状态置 `Expired`，**保留会话记录**（静默续期要用它，
  用户也能看到上次登录的是哪个号）。
- 「退出登录」路径：`removeAllCookies` + `flush` + `WebStorage.deleteAllData`
  + 删除加密会话文件 + `Gocore.setQMCCookie("")`。

---

## 4. 登录态管理

### 有效性校验：**宁可信其有**

依赖的 `musicu.fcg` 网关（`GetLoginUserInfo`）不是公开 API，因此判定策略刻意保守：

| 服务端信号 | 结论 | 动作 |
|---|---|---|
| `code == 0` | `Valid` | 保持登录 |
| `code ∈ {1000, 1001, 2000}` 或响应含「请先登录 / 登录已失效」 | `Expired` | 发 `NeedRelogin` 事件 |
| 网络错误（DNS / 超时 / 连接重置） | `Unknown` | **不登出**，退回本地有效期判断 |
| 响应无法解析（接口改版 / 风控） | `Unknown` | 同上 |

**为什么网络异常不登出**：否则用户在地铁里断网一次，回来就被踢下线、要重新输密码，
这是最差的体验。本地有效期是兜底判据。

### 失效检测

1. **冷启动**：`QQSessionManager.init()` 本地判定；未过期则立即把 Cookie 重新注入 Go 层。
2. **后台静默确认**：`MainViewModel.init` 发起一次 `verify(force = false)`（不打网络，
   除非本地判定即将过期）。
3. **业务侧埋点**：解密返回「缺密钥」且文件是 QQ 音乐格式时，
   调用 `QQSessionManager.invalidate(reason)` 直接把登录态标脏——
   用户看到的是「登录已失效，请重新登录」，而不是对着「解密失败」猜原因。
4. **主动校验**：设置页的「校验」按钮，`verify(force = true)`。

### 自动重登

**不做后台静默自动重登**，原因是 QQ 音乐没有对第三方开放的 refresh 通道——
`psrf_qqrefresh_token` 属于 QQ互联 OAuth，换不回音乐密钥。老 Cookie 死了就是死了。

替代方案是**静默续期（silent refresh）**：
`QQLoginActivity` 支持 `silent = true` 模式，带着已保存的 Cookie 重新加载
`LOGIN_URL`。若 `p_skey` 仍有生命，ptlogin2 会直接 302 回 `s_url`
并下发**新的** `qqmusic_key`，用户全程看不到登录页（前端盖一层遮罩）。

- 成功 → 刷新会话，有效期顺延，`LoginOutcome.Success(silent = true)`
- 12 秒内没跳走 → `SilentRefreshFailed` → 前端自动摘掉遮罩、把登录页交给用户，
  不用重新进设置页再点一次（`vm.continueInteractively()`）

设置页的「重新登录」按钮永远走静默模式优先：能续就不用再输一次密码。

### 退出登录

清 Cookie → 清 WebStorage → 删加密会话文件 → 清 Go 层凭据 → 状态归零。
四处缺一不可，否则会出现「已退出登录但解密仍然成功」的幽灵状态。

---

## 5. 权限与配置

### 权限

| 权限 | 必要性 | 说明 |
|---|---|---|
| `INTERNET` | ✅ 必需 | 加载登录页、校验接口（工程已有） |
| `ACCESS_NETWORK_STATE` | 建议 | 登录前判断有无网络，把「网络异常」和「用户没登录」区分开 |
| 存储类权限 | ❌ 不需要 | Cookie 走应用私有目录 + Keystore，全程不需要任何存储权限 |

### AndroidManifest

```xml
<activity
    android:name=".auth.QQLoginActivity"
    android:exported="false"                              <!-- 防借壳钓鱼 -->
    android:configChanges="orientation|screenSize|keyboardHidden|uiMode
                           |smallestScreenSize|screenLayout"  <!-- 防重建丢流程 -->
    android:windowSoftInputMode="adjustResize"            <!-- 键盘上推页面 -->
    android:hardwareAccelerated="true"
    android:theme="@style/Theme.KugoMobile" />

<queries>                                                 <!-- Android 11+ 包可见性 -->
    <package android:name="com.tencent.mobileqq" />
    <package android:name="com.tencent.mm" />
</queries>
```

### 注意事项

1. **`exports=false` 不能省**：导出后任意应用都能拉起本页面并拿到 `setResult`，
   形成借壳钓鱼面。
2. **不要设置 `usesCleartextTraffic=true`**：本模块全部走 HTTPS，保持默认 `false`。
3. **`configChanges` 里必须带 `smallestScreenSize | screenLayout`**：
   否则分屏 / 折叠屏展开会重建 Activity，正在走的登录流程直接丢失。
4. **iOS 侧（Info.plist）**：若后续做 iOS 版，需要
   `NSAppTransportSecurity` 保持默认（全 HTTPS），
   `WKWebView` 用 `WKWebsiteDataStore.default()` 才能共用 Cookie 存储；
   抓取用 `WKHTTPCookieStore.getAllCookies`（iOS 11+）。
   注意 `WKWebView` 与 `SFSafariViewController` **不共享** Cookie。
5. **WebView 内核缺失**：部分裁剪 ROM 会移掉 WebView，`new WebView()` 直接抛
   `AndroidRuntimeException`。`QQLoginWebViewFactory.isWebViewAvailable()` 提前探测，
   缺失时直接返回可读的失败文案而不是崩溃。
6. **内核版本告警**：低于 86 的旧内核在 ptlogin2 上会出各种渲染问题，
   检测到就给一句提示，引导用户去应用商店更新 Android System WebView。

---

## 6. 异常分支矩阵

| # | 场景 | 触发点 | 终态 | UI 表现 | 后续动作 |
|---|---|---|---|---|---|
| 1 | 登录成功 | Cookie 出现 `qqmusic_key` | `Success` | 「登录成功」浮层，650ms 后自动关闭 | 落盘 + 注入 Go + 校验 |
| 2 | 登录成功但校验失败 | 拿到 key 但 `musicu` 回未登录 | `Failed(COOKIE_MISSING)` | 错误卡片 | 引导重试 |
| 3 | 账号密码错误 / 被限制 | 页面自身提示 | 停留页面 | 提示「请在页面内登录」 | 用户可重输 |
| 4 | 用户主动取消 | 返回键 / 取消按钮 | `UserCancelled` | 静默返回，不报错 | 主界面不弹横幅 |
| 5 | Cookie 过期 | 冷启动 / 校验 / 业务埋点 | `VerifyFlag.Expired` | 「登录已失效」+ 重新登录按钮 | 发 `NeedRelogin` 事件 |
| 6 | 静默续期失败 | 12s 内未跳走 | `SilentRefreshFailed` | 遮罩消失，露出登录页 | 自动转交互式登录 |
| 7 | 网络不可用 | 主文档错误码 ∈ 网络类 | `NetworkError` | 「网络异常」卡片 + 重试 | 提示检查网络 |
| 8 | 连接超时 | 同上 | `NetworkError` | 同上 | 同上 |
| 9 | 用户操作超时 | 5 分钟无结果 | `Timeout` / `NetworkError` | 「登录超时」+ 重试 | 未操作过判定为网络问题 |
| 10 | 服务端 5xx | `onReceivedHttpError` | 不判终态 | 底部状态条提示「服务器暂时不可用」 | 继续等待或用户取消 |
| 11 | SSL 证书错误 | `onReceivedSslError` | `Failed(SSL_ERROR)` | 「证书校验失败」 | `handler.cancel()`，不放行 |
| 12 | 渲染进程崩溃 | `onRenderProcessGone` | `WebViewUnavailable` | 「请重试」 | 返回 `true` 防系统杀进程 |
| 13 | WebView 内核缺失 | `isWebViewAvailable` | `WebViewUnavailable` | 提示安装 Android System WebView | 不进入登录页 |
| 14 | 验证码 / 滑块 / 短信验证 | URL 命中风控域名 | 继续 `RiskChallenge` | 底部状态条换文案，不拦截 | 用户在页面内完成 |

---

## 7. 已知风险与应对

### 7.1 跨域与 Cookie 域限制

| 风险 | 应对 |
|---|---|
| 跨域 iframe 写不回 Cookie | 必须开 `setAcceptThirdPartyCookies(webView, true)`；这是「授权成功但拿不到 key」的第一嫌疑人 |
| 同一字段在多个域上值不同 | 多域名合并，按「域越具体越靠后」的顺序覆盖 |
| `SameSite` 收紧导致 iframe 不回传 | 系统 WebView 对 `SameSite=None` 的处理跟随 Chrome；若出现回归，退路是改用 `s_url` 指向 `i.y.qq.com` 让 Cookie 落在同一级域 |
| `CookieManager` 是进程级单例，与其他 WebView 共享 | 当前只有一个 WebView，尚不冲突；若将来引入内嵌帮助页，需接受共享或改用 `WebViewCompat` 做独立拦截 |

### 7.2 二次验证 / 风控弹窗

- **绝不拦截**。滑块、短信验证都发生在 `t.captcha.qq.com` / `ssl.ptlogin2.qq.com`，
  拦截等于直接失败。命中风控域名时只是把底部文案换成「请在页面内完成安全验证」。
- **降低触发概率**：UA 用真实 Chrome 移动 UA（剥 `wv` 标记）；不伪造机型；
  不从同一个 Cookie 串高频轮询登录页。
- **被风控之后的恢复**：引导用户改用手机 QQ 扫码登录（同一个 `ptlogin2` 页面提供），
  或换网络（切换 Wi-Fi / 蜂窝）后重试。**不要在应用里做自动重试循环**，
  高频请求只会让风控升级。

### 7.3 与系统浏览器 / 应用内登录态共享

- **与手机 QQ 客户端不共享**：QQ 客户端用的是自己的私有 Cookie 存储，无法互通。
  因此「手机上 QQ 已经登录」不代表 WebView 里已登录，用户仍要走一次登录页。
  （可用 `<queries>` 检测本机是否装了 QQ，给出「用扫码登录更快」的引导文案。）
- **与系统 Chrome 不共享**：WebView 与 Chrome 是两套 Cookie 存储。
- **与 App 内其他 WebView 共享**：`CookieManager` 是进程级单例，这是**双刃剑**——
  省事，但意味着任何 WebView 都能看到登录 Cookie。缓解措施：
  1. 登录完成后 `QQWebCookieJar.flush()` 后不再在别的 WebView 里加载 QQ 域页面；
  2. 退出登录时不仅清 Cookie，还清 `WebStorage` 与表单数据；
  3. 不要在登录页上 `addJavascriptInterface`。
- **iOS 侧额外的坑**：`WKWebView` 与 `SFSafariViewController` 不共享 Cookie 存储，
  iOS 版必须统一用 `WKWebView` + `WKWebsiteInfoDataStore.default()`。

### 7.4 其他

| 风险 | 应对 |
|---|---|
| 凭据泄露 | Keystore 加密落盘；UI / 日志一律走 `QQCookieExtractor.mask()` 脱敏 |
| 接口改版导致校验误判 | 只有服务端**明确**回未登录才判 `Expired`，其余一律 `Unknown` 退回本地判定 |
| 进程被杀丢 Cookie | 抓到即 `flush()`；会话本身已加密落盘，重启自动恢复 |
| WebView 内存泄漏 | Activity `onDestroy` 时 `stopLoading → loadUrl("about:blank") → destroy()`；ViewModel 不持有 WebView |

---

## 8. 文件清单

```
mobile/app/src/main/java/com/kugo/mobile/
├── auth/
│   ├── QQAuthConfig.kt              # 常量与 URL 构造
│   ├── QQAuthResult.kt              # LoginOutcome / LoginErrorCode
│   ├── QQLoginWebViewFactory.kt     # WebView 构造与加固
│   ├── QQWebCookieJar.kt            # CookieManager 门面
│   ├── QQCookieExtractor.kt         # Cookie 解析 / 合并 / 脱敏
│   ├── QQSession.kt                 # 会话模型
│   ├── QQSessionStore.kt            # Keystore 加密持久化
│   ├── QQSessionValidator.kt        # 在线 + 本地校验
│   ├── QQSessionManager.kt          # 登录态唯一持有者
│   ├── QQLoginViewModel.kt          # 登录流程状态机
│   └── QQLoginActivity.kt           # 容器 Activity
└── ui/
    └── QQLoginScreen.kt             # Compose UI + WebView 宿主

改动既有文件：
├── AndroidManifest.xml              # +ACCESS_NETWORK_STATE、+QQLoginActivity、+queries
├── ui/MainViewModel.kt              # +qqAccount 状态、+登录结果收口、+诊断信息
├── ui/SettingsScreen.kt             # +「QQ 音乐账号」卡片
└── MainActivity.kt                  # +登录页 Launcher、+失效事件订阅
```

### 调用方式

```kotlin
// 打开登录页（已登录时优先走静默续期）
val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    val (ok, message) = QQLoginActivity.readOutcome(result.data)
    vm.onQQLoginResult(ok, message)
}
launcher.launch(QQLoginActivity.intent(context, silent = account.session != null))
```

---

## 9. 图标资源

`tools/gen_icons.py` 从给定源图生成全套启动图标：

```bash
python tools/gen_icons.py <源图> mobile/app/src/main/res "#EDE0F9"
```

| 产物 | 尺寸（mdpi→xxxhdpi） | 用途 |
|---|---|---|
| `mipmap-*/ic_launcher.png` | 48 / 72 / 96 / 144 / 192 | API < 26 传统方形图标 |
| `mipmap-*/ic_launcher_round.png` | 同上 | API < 26 圆形图标 |
| `mipmap-*/ic_launcher_foreground.png` | 108 / 162 / 216 / 324 / 432 | 自适应图标前景（108dp 画布，主体按 68% 居中，保证任意遮罩下铺满不露边） |
| `mipmap-anydpi-v26/ic_launcher*.xml` | — | 自适应图标定义，背景取源图主色调 `#EDE0F9` |
