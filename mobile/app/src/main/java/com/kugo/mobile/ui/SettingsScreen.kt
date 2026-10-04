package com.kugo.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kugo.mobile.auth.QQAccountState
import com.kugo.mobile.auth.VerifyFlag
import com.kugo.mobile.core.AudioFormat
import com.kugo.mobile.data.ThemeMode

/**
 * 设置页：界面、输出、QQ 账号、密钥导入、格式清单。
 */
@Composable
fun SettingsScreen(
    formats: List<AudioFormat>,
    outputTreeName: String,
    privateOutputPath: String,
    outputToCustomDir: Boolean,
    deletePrivateCopy: Boolean,
    hasKggKeys: Boolean,
    hasQmcKeys: Boolean,
    qqAccount: QQAccountState,
    themeMode: ThemeMode,
    onBack: () -> Unit,
    onPickOutputTree: () -> Unit,
    onToggleCustomOutput: (Boolean) -> Unit,
    onToggleDeletePrivateCopy: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode, Offset) -> Unit,
    onQQLogin: () -> Unit,
    onQQVerify: () -> Unit,
    onQQLogout: () -> Unit,
    onImportKggKeyFile: () -> Unit,
    onImportKggDatabase: () -> Unit,
    onPasteText: (forQmc: Boolean) -> Unit,
    onClearCredentials: () -> Unit,
    onShowDisclaimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // --- 外观 ---
        item {
            SectionCard(title = "外观") {
                Text(
                    text = "切换时新配色会从你点按的位置扩散开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                ThemeMode.values().forEach { mode ->
                    ThemeModeOption(
                        mode = mode,
                        label = when (mode) {
                            ThemeMode.System -> "跟随系统"
                            ThemeMode.Light -> "浅色"
                            ThemeMode.Dark -> "深色"
                        },
                        selected = themeMode == mode,
                        onSelect = onThemeModeChange,
                    )
                }
            }
        }

        // --- 输出位置与清理（一对联动开关） ---
        item {
            SectionCard(title = "输出位置") {
                SwitchRow(
                    title = "输出到自定义目录",
                    subtitle = if (outputToCustomDir) "当前：$outputTreeName" else "当前：应用私有目录",
                    checked = outputToCustomDir,
                    onCheckedChange = onToggleCustomOutput,
                )

                Divider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                )

                // 依赖开关：父开关关闭时它没有意义，因此置灰并跟随关闭。
                RelatedSwitchRow(
                    title = "发布后删除私有目录副本",
                    subtitle = if (outputToCustomDir) {
                        "关掉可留一份兜底，代价是占用双份空间"
                    } else {
                        "需要先打开「输出到自定义目录」"
                    },
                    checked = deletePrivateCopy && outputToCustomDir,
                    enabled = outputToCustomDir,
                    pulseKey = outputToCustomDir,
                    onCheckedChange = onToggleDeletePrivateCopy,
                )

                Spacer(Modifier.height(10.dp))
                if (outputToCustomDir) {
                    OutlinedButton(onClick = onPickOutputTree) {
                        Icon(Icons.Default.Folder, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重新选择目录")
                    }
                } else {
                    Text(
                        text = "文件保存在 $privateOutputPath，可在系统文件管理器中找到本应用目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // --- QQ 音乐账号 ---
        item {
            SectionCard(title = "QQ 音乐账号") {
                QQAccountSection(
                    account = qqAccount,
                    onLogin = onQQLogin,
                    onVerify = onQQVerify,
                    onLogout = onQQLogout,
                )
            }
        }

        // --- 酷狗密钥 ---
        item {
            SectionCard(title = "酷狗密钥（KGG 格式必需）") {
                StatusLine(
                    ok = hasKggKeys,
                    okText = "已导入密钥，KGG 文件可直接解密",
                    missingText = "未导入。.kgg 文件需要密钥才能解密",
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onImportKggKeyFile) {
                        Text("导入 kgg.key")
                    }
                    OutlinedButton(onClick = onImportKggDatabase) {
                        Text("导入 KGMusicV3.db")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onPasteText(false) }) {
                    Icon(Icons.Default.ContentPaste, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("粘贴密钥文本")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "文本格式：每行一条，形如 音频哈希\$密钥",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // --- QQ音乐 ekey ---
        item {
            SectionCard(title = "QQ音乐 ekey（MFLAC / MGG 必需）") {
                StatusLine(
                    ok = hasQmcKeys,
                    okText = "已导入 ekey",
                    missingText = "未导入。MFLAC / MGG 需要对应文件的 ekey",
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { onPasteText(true) }) {
                    Icon(Icons.Default.ContentPaste, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("粘贴 ekey 文本")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "文本格式：每行一条，形如 mediaMid\$ekey；也可写 文件名\$ekey 作为兜底匹配。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                HintBox(
                    title = "自动获取 ekey",
                    body = "登录上方 QQ 账号后，应用会用保存的 Cookie 自动向 QQ 音乐换取 ekey，" +
                        "不必再手动粘贴。Cookie 失效时按提示重新登录即可。",
                )
            }
        }

        // --- 危险操作 ---
        item {
            SectionCard(title = "密钥管理") {
                Text(
                    "密钥只保存在本应用私有目录，其他应用无法读取。清空后需重新导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onClearCredentials) {
                    Icon(Icons.Default.Delete, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("清空全部密钥")
                }
            }
        }

        // --- 免责声明 ---
        item {
            SectionCard(title = "免责声明") {
                Text(
                    Disclaimer.SUMMARY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onShowDisclaimer) {
                    Icon(Icons.Default.Info, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("查看完整声明")
                }
            }
        }

        // --- 支持格式 ---
        item {
            Text(
                "支持格式（${formats.size}）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        items(formats, key = { it.ext }) { fmt ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(fmt.ext, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            fmt.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (fmt.needExtraKey) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "需要密钥",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    PlatformTag(fmt.platform)
                }
            }
        }
    }
}

/**
 * 主题模式选项。
 *
 * 关键点：把选项自身的**屏幕坐标中心**回传给调用方，让「圆形主题切换」
 * 以用户真正点到的那个位置为圆心扩散。用 `positionInRoot` 而不是本地坐标，
 * 是因为遮盖层挂在根布局上，两套坐标系必须对齐。
 */
@Composable
private fun ThemeModeOption(
    mode: ThemeMode,
    label: String,
    selected: Boolean,
    onSelect: (ThemeMode, Offset) -> Unit,
) {
    var centerInRoot by remember { mutableStateOf(Offset.Zero) }

    val fill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = Motion.snappy<Float>(),
        label = "themeOptionFill",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                centerInRoot = coords.positionInRoot() +
                    Offset(coords.size.width / 2f, coords.size.height / 2f)
            }
            .clip(RoundedCornerShape(10.dp))
            .clickable { onSelect(mode, centerInRoot) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 单选圈：外环固定，内点随选中比例放大。
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(50))
                .border(
                    width = 1.6.dp,
                    color = androidx.compose.ui.graphics.lerp(
                        MaterialTheme.colorScheme.outline,
                        MaterialTheme.colorScheme.primary,
                        fill,
                    ),
                    shape = RoundedCornerShape(50),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size((10f * fill).dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // 对勾始终占位，只让 alpha 跟随 fill。
        // 早先用 if (fill > 0.05f) 条件渲染：图标会在阈值处「突然冒出来」，
        // 而且它一出现就改变这一行的宽度，把左边的文字挤一下。
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp).graphicsLayer { alpha = fill },
        )
    }
}

/** 普通设置开关。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 联动开关 —— 开关联动反馈（Ripple Feedback for Related Switches）。
 *
 * 两件事：
 *  1. **依赖**：父开关关闭时本开关置灰并失效，杜绝「设了但不起作用」的幽灵配置；
 *  2. **涟漪**：父开关一变，本行外侧扩散一圈描边，把「是你的变化影响到我」
 *     这条因果关系直接画出来——否则用户只会觉得下面这一项莫名其妙变灰了。
 */
@Composable
private fun RelatedSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    pulseKey: Any?,
    onCheckedChange: (Boolean) -> Unit,
) {
    val pulse = rememberRipplePulse(pulseKey)

    val labelColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        animationSpec = Motion.fade(220),
        label = "relatedLabel",
    )
    val subtitleColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
        animationSpec = Motion.fade(220),
        label = "relatedSub",
    )

    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = labelColor)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        }

        if (pulse > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = pulse * 0.55f
                        // 从内向外扩散：pulse 从 1 衰减到 0，缩放从 0.96 涨到 1.0。
                        val s = 1f - 0.04f * pulse
                        scaleX = s
                        scaleY = s
                    }
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp),
                    ),
            )
        }
    }
}

/**
 * QQ 账号卡片。
 *
 * 三种形态分别对应三种处理路径：
 *  - 未登录：给出登录入口与「为什么要登录」的说明；
 *  - 已登录：展示账号、有效期、存储方式，并提供校验 / 重新登录 / 退出；
 *  - 已过期：把「重新登录」提到第一位，并说明失效原因。
 */
@Composable
private fun QQAccountSection(
    account: QQAccountState,
    onLogin: () -> Unit,
    onVerify: () -> Unit,
    onLogout: () -> Unit,
) {
    val session = account.session

    if (session == null) {
        StatusLine(
            ok = false,
            okText = "",
            missingText = "未登录。登录后可自动换取 QQ 音乐 ekey",
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onLogin) {
            Icon(Icons.Default.Login, null, Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("登录 QQ 账号")
        }
        Spacer(Modifier.height(10.dp))
        HintBox(
            title = "登录是怎么完成的？",
            body = "应用内会打开 QQ 官方网页登录页，你正常输入账号密码即可。" +
                "登录成功后应用只在本地读取登录 Cookie，用于向 QQ 音乐换取 ekey；" +
                "Cookie 使用系统密钥库加密保存，不会上传到任何服务器。",
        )
        return
    }

    val expired = account.isExpired
    StatusLine(
        ok = !expired,
        okText = if (account.verify == VerifyFlag.Unknown) "已登录（有效期待确认）" else "已登录",
        missingText = "登录已失效：${account.message ?: "凭据过期"}",
    )

    Spacer(Modifier.height(10.dp))
    DetailRow("QQ 号", session.uin.ifEmpty { "未知" })
    DetailRow("登录时间", session.capturedAtText)
    DetailRow("有效期", "${session.expiresAtText}（${session.remainingText()}）")
    DetailRow("校验状态", verifyText(account.verify))
    DetailRow("存储方式", if (account.encryptedAtRest) "AndroidKeyStore 加密" else "明文（设备密钥库不可用）")

    Spacer(Modifier.height(8.dp))
    ExpandableText(
        text = "Cookie 预览：${session.maskedCookie}",
        collapsedMaxLines = 2,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        minLengthForToggle = 40,
    )

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onLogin) {
            Icon(Icons.Default.Login, null, Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (expired) "重新登录" else "更换账号")
        }
        OutlinedButton(onClick = onVerify) {
            Icon(Icons.Default.VerifiedUser, null, Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("校验")
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onLogout) {
        Icon(Icons.Default.Delete, null, Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text("退出登录")
    }

    if (expired) {
        Spacer(Modifier.height(10.dp))
        HintBox(
            title = "为什么需要重新登录？",
            body = "QQ 的音乐密钥有效期通常只有一天左右，过期后接口会拒绝访问。" +
                "点击「重新登录」会用已保存的凭据先尝试自动续期，失败时才需要你手动输入密码。",
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun verifyText(flag: VerifyFlag): String = when (flag) {
    VerifyFlag.Valid -> "服务端确认有效"
    VerifyFlag.Expired -> "服务端确认失效"
    VerifyFlag.Unknown -> "无法确认（网络或接口异常）"
    VerifyFlag.Unchecked -> "尚未校验"
}

/** 设置页统一的分组卡片外框。 */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** 说明块。正文较长，统一走可展开文本，避免设置页被大段文字淹没。 */
@Composable
private fun HintBox(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            ExpandableText(
                text = body,
                collapsedMaxLines = 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLengthForToggle = 58,
            )
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, okText: String, missingText: String) {
    val color = if (ok) MaterialTheme.colorScheme.primary else Color(0xFFE8A33D)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ok) Icons.Default.Check else Icons.Default.Lock,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (ok) okText else missingText,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}
