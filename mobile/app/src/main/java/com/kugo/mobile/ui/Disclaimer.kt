package com.kugo.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 免责声明的正文。
 *
 * 集中放一处，是因为它有两个出口：首次启动的强制确认页、设置页里的只读全文页。
 * 两边必须是**同一份文本**——如果各写一份，改了一处忘了另一处，
 * 用户看到的两次声明就不一致了。
 */
object Disclaimer {

    const val TITLE = "免责声明与使用条款"

    /** 设置页那一节用的摘要，只说明「它不会替你拿到你没有的东西」。 */
    const val SUMMARY =
        "本应用只在本机把你自己已有的加密音频转成通用格式，不提供任何音乐内容，" +
            "也不与各音乐平台存在隶属或合作关系。"

    data class Section(val heading: String, val body: String)

    val SECTIONS: List<Section> = listOf(
        Section(
            "一、本应用做什么",
            "本应用是一个纯本地的音频格式转换工具：把你设备上已经存在的加密音频文件，" +
                "转换为通用的 MP3 / FLAC 等格式。全部处理都在本机完成，" +
                "音频内容不会被上传到任何地方。",
        ),
        Section(
            "二、本应用不提供什么",
            "本应用不提供、不存储、不分发任何音乐作品，也不提供账号、破解或下载服务。" +
                "它无法让你获得你本来就无权访问的文件——" +
                "它只是把你已经拿到手的文件换个容器。",
        ),
        Section(
            "三、你需要承担的责任",
            "1. 你只能处理你已合法取得使用权的文件，例如已经购买、或已订阅并下载到本机的音频。\n" +
                "2. 不得将转换结果用于商业用途、二次分发、公开传播，" +
                "或任何侵犯著作权的行为。\n" +
                "3. 因使用本应用产生的一切后果，由使用者自行承担。",
        ),
        Section(
            "四、密钥与账号",
            "解密所需的密钥（kgg / ekey）与 QQ 音乐登录凭据全部由你自己提供。" +
                "它们只保存在本机应用私有目录，登录 Cookie 使用 Android 系统密钥库加密；" +
                "本应用不会把它们上传到任何服务器，也不会发送给开发者。" +
                "你可以随时在设置页清空全部密钥与登录状态。",
        ),
        Section(
            "五、与第三方的关系",
            "本应用与酷狗音乐、网易云音乐、酷我音乐、腾讯 QQ 音乐及其关联公司" +
                "没有任何隶属、合作或授权关系。文中出现的商标与产品名称" +
                "仅用于说明所兼容的文件格式，相关权利归各自所有者所有。\n" +
                "本应用包含 Go 标准库、AndroidX / Jetpack Compose 等开源组件，" +
                "它们各自遵循原有许可协议。",
        ),
        Section(
            "六、无担保",
            "本应用按「现状」提供，不附带任何明示或默示的担保。" +
                "开发者不对文件损坏、数据丢失、转换失败或任何间接损失承担责任。" +
                "请在操作前自行备份原始文件。\n" +
                "继续使用本应用即表示你接受本声明。若条款发生变更，" +
                "我们会在应用内提示；若你不同意变更后的条款，请停止使用并卸载本应用。",
        ),
    )
}

/** 声明正文渲染。两个页面共用。 */
@Composable
fun DisclaimerContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Disclaimer.SECTIONS.forEach { section ->
            Column {
                Text(
                    text = section.heading,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    // 正文行高放宽一点：中文长段落挨得太紧会很难读。
                    text = section.body,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 首次启动的确认页。
 *
 * 根组件在未同意时只组合这一页，因此不需要额外吞掉指针事件；按钮与正文滚动可以正常
 * 接收事件，同时主界面也不存在点击穿透或绕过声明的路径。返回键等同于「不同意并退出」。
 */
@Composable
fun DisclaimerGate(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    BackHandler { onDecline() }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = KmcDimens.ReadableContentMaxWidth)
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = KmcDimens.ScreenPadding, vertical = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = Disclaimer.TITLE,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.height(14.dp))
                DisclaimerContent(Modifier.weight(1f))

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "点击「我已阅读并同意」表示你已阅读、理解并接受以上全部条款。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                DisclaimerActions(onAccept = onAccept, onDecline = onDecline)
            }
        }
    }
}

@Composable
private fun DisclaimerActions(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 420.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DisclaimerAcceptButton(onAccept, Modifier.fillMaxWidth())
                DisclaimerDeclineButton(onDecline, Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DisclaimerDeclineButton(onDecline, Modifier.weight(1f))
                DisclaimerAcceptButton(onAccept, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DisclaimerAcceptButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier) {
        Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("我已阅读并同意")
    }
}

@Composable
private fun DisclaimerDeclineButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text("不同意并退出")
    }
}

/** 设置页进入的只读全文页。 */
@Composable
fun DisclaimerScreen(onBack: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = KmcDimens.ReadableContentMaxWidth)
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = KmcDimens.ScreenPadding),
            ) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "免责声明",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(10.dp))
                DisclaimerContent(Modifier.weight(1f).padding(bottom = 20.dp))
            }
        }
    }
}
