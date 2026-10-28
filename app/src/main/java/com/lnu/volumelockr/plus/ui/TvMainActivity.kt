package com.lnu.volumelockr.plus.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.lnu.volumelockr.plus.BuildConfig
import com.lnu.volumelockr.plus.R
import com.lnu.volumelockr.plus.service.TvRemoteServer
import com.lnu.volumelockr.plus.service.VolumeProvider
import com.lnu.volumelockr.plus.service.VolumeService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 專為 Android 電視大螢幕設計之主頁面活動
 * 遵循官方電視介面設計規範並使用電視專用元件庫打造
 */
@OptIn(ExperimentalTvMaterial3Api::class)
class TvMainActivity : BaseSecuredActivity() {

    private var service: VolumeService? = null
    private var isBound = false
    private var volumeListState by mutableStateOf<List<Volume>>(emptyList())
    private var locksMapState by mutableStateOf<Map<Int, Int>>(emptyMap())
    private var hideTvUnlockUiState by mutableStateOf(true)

    private var currentIpState by mutableStateOf("")
    private var currentPinState by mutableStateOf("")
    private var pinRemainingSecState by mutableIntStateOf(0)

    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshData()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as VolumeService.LocalBinder).getService()
            service = svc
            isBound = true
            refreshData()
            svc.registerOnVolumeChangeListener(Handler(Looper.getMainLooper())) {
                refreshData()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            service = null
        }
    }

    override fun getSecuredContentView(): View? = window.decorView.findViewById(android.R.id.content)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 預先取得本機音量聲道清單以利畫面初次呈現時即具備焦點定位目標
        volumeListState = VolumeProvider(this).getVolumes()
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        hideTvUnlockUiState = prefs.getBoolean("hide_tv_unlock_ui", true)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TvMainRoot(
                    volumes = volumeListState,
                    locks = locksMapState,
                    hideTvUnlockUi = hideTvUnlockUiState,
                    ipAddress = currentIpState,
                    pairingPin = currentPinState,
                    pinRemainingSec = pinRemainingSecState,
                    onLockAllClicked = { handleLockAction() },
                    onUnlockAllClicked = { unlockAll() },
                    onUnlockStream = { stream -> unlockStream(stream) },
                    onNoLocksToast = { Toast.makeText(this@TvMainActivity, R.string.tv_no_locks_toast, Toast.LENGTH_SHORT).show() },
                    onPairClicked = { startActivity(Intent(this@TvMainActivity, PairingActivity::class.java)) },
                    onAboutClicked = { startActivity(Intent(this@TvMainActivity, AboutActivity::class.java)) },
                    onAdjustVolume = { stream, delta -> adjustVolume(stream, delta) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            uiUpdateReceiver,
            IntentFilter("com.lnu.volumelockr.plus.ACTION_UI_UPDATE"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        bindService(Intent(this, VolumeService::class.java), connection, Context.BIND_AUTO_CREATE)
        updateNetworkAndPin()
    }

    override fun onPause() {
        unregisterReceiver(uiUpdateReceiver)
        service?.unregisterOnModeChangeListener()
        if (isBound) {
            runCatching { unbindService(connection) }
            isBound = false
        }
        service = null
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            handleLockAction()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleLockAction() {
        val svc = service ?: return
        val isAllLocked = svc.getVolumes().isNotEmpty() && svc.getLocks().size == svc.getVolumes().size
        if (isAllLocked) {
            if (!hideTvUnlockUiState) {
                unlockAll()
            } else {
                Toast.makeText(this, R.string.tv_locked_all_toast, Toast.LENGTH_SHORT).show()
            }
        } else {
            lockAll()
        }
    }

    private fun lockAll() {
        val svc = service ?: return
        svc.getVolumes().forEach { svc.addLock(it.stream, it.value) }
        VolumeService.start(this)
        svc.startLocking()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            svc.tryShowNotification()
        }
        refreshData()
        Toast.makeText(this, R.string.tv_locked_all_toast, Toast.LENGTH_SHORT).show()
    }

    private fun unlockAll() {
        val svc = service ?: return
        svc.getVolumes().forEach { svc.removeLock(it.stream) }
        svc.stopLocking()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            svc.tryHideNotification()
        }
        refreshData()
        Toast.makeText(this, R.string.tv_unlocked_all_toast, Toast.LENGTH_SHORT).show()
    }

    private fun unlockStream(stream: Int) {
        val svc = service ?: return
        svc.removeLock(stream)
        if (svc.getLocks().isEmpty()) {
            svc.stopLocking()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                svc.tryHideNotification()
            }
        }
        refreshData()
        Toast.makeText(this, R.string.tv_unlocked_stream_toast, Toast.LENGTH_SHORT).show()
    }

    private fun adjustVolume(stream: Int, delta: Int) {
        val svc = service ?: return
        if (svc.getLocks().containsKey(stream)) {
            Toast.makeText(this, R.string.tv_locked_all_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audioManager.getStreamVolume(stream)
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audioManager.getStreamMinVolume(stream) else 0
        val max = audioManager.getStreamMaxVolume(stream)
        val target = (current + delta).coerceIn(min, max)
        if (target != current) {
            audioManager.setStreamVolume(stream, target, 0)
            refreshData()
        }
    }

    private fun refreshData() {
        val svc = service ?: return
        volumeListState = svc.getVolumes().map { it.copy() }
        locksMapState = HashMap(svc.getLocks())
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        hideTvUnlockUiState = prefs.getBoolean("hide_tv_unlock_ui", true)
    }

    private fun updateNetworkAndPin() {
        currentIpState = getLocalIpAddress() ?: "127.0.0.1"
        val (pin, remainingSec) = TvRemoteServer.getOrGeneratePairingPin()
        currentPinState = pin
        pinRemainingSecState = remainingSec
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            val preferredInterfaces = interfaces.filter { intf ->
                intf.isUp && !intf.isLoopback && (intf.name.startsWith("wlan") || intf.name.startsWith("eth")) &&
                    !intf.name.contains("p2p")
            }
            val candidateInterfaces = preferredInterfaces.ifEmpty {
                interfaces.filter { intf ->
                    intf.isUp && !intf.isLoopback && !intf.name.contains("p2p") &&
                        !intf.name.contains("dummy") && !intf.name.contains("tun")
                }
            }.ifEmpty { interfaces }

            for (networkInterface in candidateInterfaces) {
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val host = address.hostAddress
                        if (host != null && host != "127.0.0.1") {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}

/**
 * 根容器元件
 * 繪製深暗紫色漸層背景並設定標準電視安全邊距
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvMainRoot(
    volumes: List<Volume>,
    locks: Map<Int, Int>,
    hideTvUnlockUi: Boolean,
    ipAddress: String,
    pairingPin: String,
    pinRemainingSec: Int,
    onLockAllClicked: () -> Unit,
    onUnlockAllClicked: () -> Unit,
    onUnlockStream: (stream: Int) -> Unit,
    onNoLocksToast: () -> Unit,
    onPairClicked: () -> Unit,
    onAboutClicked: () -> Unit,
    onAdjustVolume: (stream: Int, delta: Int) -> Unit
) {
    var livePin by remember { mutableStateOf(pairingPin) }
    var liveSec by remember { mutableIntStateOf(pinRemainingSec) }

    LaunchedEffect(Unit) {
        while (isActive) {
            val (pin, sec) = TvRemoteServer.getOrGeneratePairingPin()
            livePin = pin
            liveSec = sec
            delay(1000L)
        }
    }

    val backgroundGradient = Brush.radialGradient(
        colors = listOf(
            Color(0xFF261D36),
            Color(0xFF181324),
            Color(0xFF121017)
        ),
        center = Offset(240f, 180f),
        radius = 1400f
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
            .padding(horizontal = 48.dp, vertical = 27.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            // 左側區域佔比約百分之三十五
            LeftPane(
                modifier = Modifier
                    .weight(0.35f)
                    .fillMaxHeight(),
                lockedCount = locks.size,
                totalCount = volumes.size,
                isAllLocked = volumes.isNotEmpty() && locks.size == volumes.size,
                hideTvUnlockUi = hideTvUnlockUi,
                onAboutClicked = onAboutClicked
            )

            // 右側區域佔比約百分之六十五
            RightPane(
                modifier = Modifier
                    .weight(0.65f)
                    .fillMaxHeight(),
                volumes = volumes,
                locks = locks,
                hideTvUnlockUi = hideTvUnlockUi,
                ipAddress = ipAddress,
                pairingPin = livePin,
                pinRemainingSec = liveSec,
                onLockAllClicked = onLockAllClicked,
                onUnlockAllClicked = onUnlockAllClicked,
                onUnlockStream = onUnlockStream,
                onNoLocksToast = onNoLocksToast,
                onPairClicked = onPairClicked,
                onAdjustVolume = onAdjustVolume
            )
        }
    }
}

/**
 * 實體遙控按鍵圖示樣式標籤
 */
@Composable
fun KeycapIconBadge(iconRes: Int) {
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2D253A))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color(0xFFF4EFFA),
            modifier = Modifier.size(15.dp)
        )
    }
}

/**
 * 左側資訊欄位
 * 包含品牌標題狀態膠囊中間關於按鈕與底部遙控操作說明
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LeftPane(
    modifier: Modifier,
    lockedCount: Int,
    totalCount: Int,
    isAllLocked: Boolean,
    hideTvUnlockUi: Boolean,
    onAboutClicked: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 頂部品牌與狀態膠囊
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF43345E)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_volumelockr_foreground),
                        contentDescription = null,
                        tint = Color(0xFFE9DDFF),
                        modifier = Modifier.size(36.dp)
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF4EFFA)
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFA59DB2)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 狀態膠囊標籤
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (isAllLocked) Color(0xFF383838) else Color(0xFF2D253A)
                    )
                    .border(
                        1.dp,
                        if (isAllLocked) Color(0xFF9E9E9E).copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(
                            if (isAllLocked) R.drawable.ic_lock else R.drawable.ic_lock_open
                        ),
                        contentDescription = null,
                        tint = if (isAllLocked) Color(0xFFBDBDBD) else Color(0xFFD0BCFF),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.locked_subtitle, lockedCount, totalCount),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isAllLocked) Color(0xFFE0E0E0) else Color(0xFFD0BCFF)
                    )
                }
            }
        }

        // 中間放置關於卡片按鈕充實左側空間
        val aboutColors = ListItemDefaults.colors(
            containerColor = Color(0xFF1E1B26),
            contentColor = Color(0xFFF4EFFA),
            focusedContainerColor = Color(0xFFF4EFFA),
            focusedContentColor = Color(0xFF121017)
        )
        val aboutScale = ListItemDefaults.scale(focusedScale = 1.03f)
        val aboutShape = ListItemDefaults.shape(shape = RoundedCornerShape(12.dp))

        ListItem(
            selected = false,
            onClick = onAboutClicked,
            colors = aboutColors,
            scale = aboutScale,
            shape = aboutShape,
            headlineContent = {
                Text(
                    text = stringResource(R.string.about),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            supportingContent = {
                Text(
                    text = stringResource(R.string.about_card_summary),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            leadingContent = {
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        // 底部遙控器操作指南卡片
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E1B26).copy(alpha = 0.85f))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.remote_guide_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD0BCFF)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeycapIconBadge(R.drawable.ic_tv_menu)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.guide_menu_key),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD8D0E3)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeycapIconBadge(R.drawable.ic_arrows_horizontal)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.guide_arrows),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD8D0E3)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeycapIconBadge(R.drawable.ic_smartphone)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (hideTvUnlockUi) {
                            stringResource(R.string.guide_phone_unlock)
                        } else {
                            stringResource(R.string.guide_tv_unlock_allowed)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD8D0E3)
                    )
                }
            }
        }
    }
}

/**
 * 取得音量聲道對應的本地化友善名稱
 */
@Composable
fun getStreamDisplayName(stream: Int, fallbackName: String): String {
    return when (stream) {
        AudioManager.STREAM_MUSIC -> stringResource(R.string.stream_music_title)
        AudioManager.STREAM_RING -> stringResource(R.string.stream_ring_title)
        AudioManager.STREAM_NOTIFICATION -> stringResource(R.string.stream_notification_title)
        AudioManager.STREAM_ALARM -> stringResource(R.string.stream_alarm_title)
        AudioManager.STREAM_VOICE_CALL -> stringResource(R.string.stream_call_title)
        else -> fallbackName
    }
}

/**
 * 右側互動操作與音量控制清單
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RightPane(
    modifier: Modifier,
    volumes: List<Volume>,
    locks: Map<Int, Int>,
    hideTvUnlockUi: Boolean,
    ipAddress: String,
    pairingPin: String,
    pinRemainingSec: Int,
    onLockAllClicked: () -> Unit,
    onUnlockAllClicked: () -> Unit,
    onUnlockStream: (stream: Int) -> Unit,
    onNoLocksToast: () -> Unit,
    onPairClicked: () -> Unit,
    onAdjustVolume: (stream: Int, delta: Int) -> Unit
) {
    val lockButtonFocusRequester = remember { FocusRequester() }
    val volumeFocusRequester = remember { FocusRequester() }
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }
    val isAllLocked = volumes.isNotEmpty() && locks.size == volumes.size

    // 依據需求預設光標聚焦於音量控制項目
    LaunchedEffect(volumes) {
        if (volumes.isNotEmpty() && !hasRequestedInitialFocus) {
            delay(50L)
            val success = runCatching {
                volumeFocusRequester.requestFocus()
                true
            }.getOrDefault(false)
            if (success) {
                hasRequestedInitialFocus = true
            }
        }
    }

    val itemColors = ListItemDefaults.colors(
        containerColor = Color(0xFF1E1B26),
        contentColor = Color(0xFFF4EFFA),
        focusedContainerColor = Color(0xFFF4EFFA),
        focusedContentColor = Color(0xFF121017),
        selectedContainerColor = Color(0xFF2E2638),
        selectedContentColor = Color(0xFFF4EFFA),
        focusedSelectedContainerColor = Color(0xFFF4EFFA),
        focusedSelectedContentColor = Color(0xFF121017)
    )

    val itemScale = ListItemDefaults.scale(focusedScale = 1.03f)
    val itemShape = ListItemDefaults.shape(shape = RoundedCornerShape(12.dp))

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 快速操作區段標題
        item {
            Text(
                text = stringResource(R.string.quick_actions_header),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD0BCFF),
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }

        // 快速操作第一項 全部鎖定
        item {
            ListItem(
                selected = isAllLocked,
                onClick = onLockAllClicked,
                colors = itemColors,
                scale = itemScale,
                shape = itemShape,
                headlineContent = {
                    Text(
                        text = if (isAllLocked) {
                            stringResource(R.string.tv_action_locked_title)
                        } else {
                            stringResource(R.string.tv_action_lock_title)
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                supportingContent = {
                    Text(
                        text = if (isAllLocked) {
                            if (hideTvUnlockUi) {
                                stringResource(R.string.tv_action_locked_summary)
                            } else {
                                stringResource(R.string.tv_action_locked_can_unlock)
                            }
                        } else {
                            stringResource(R.string.tv_action_lock_summary)
                        }
                    )
                },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_lock),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                },
                trailingContent = {
                    if (isAllLocked) {
                        Icon(
                            painter = painterResource(R.drawable.ic_lock),
                            contentDescription = null,
                            tint = Color(0xFF9E9E9E),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(lockButtonFocusRequester)
            )
        }

        // 快速操作：全部解鎖（可由手機端開關控制是否顯示於電視介面）
        if (!hideTvUnlockUi) {
            val hasLocks = locks.isNotEmpty()
            item {
                ListItem(
                    selected = false,
                    onClick = {
                        if (hasLocks) {
                            onUnlockAllClicked()
                        } else {
                            onNoLocksToast()
                        }
                    },
                    colors = itemColors,
                    scale = itemScale,
                    shape = itemShape,
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.tv_action_unlock_title),
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    supportingContent = {
                        Text(
                            text = if (hasLocks) {
                                stringResource(R.string.tv_action_unlock_summary)
                            } else {
                                stringResource(R.string.tv_action_no_locks_summary)
                            }
                        )
                    },
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_lock_open),
                            contentDescription = null,
                            tint = if (hasLocks) Color(0xFFD0BCFF) else Color(0xFFA59DB2),
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    trailingContent = {
                        if (hasLocks) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lock_open),
                                contentDescription = null,
                                tint = Color(0xFFD0BCFF),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 快速操作第二項 與手機配對 具備獨立網路與驗證碼高對比資訊盒
        item {
            ListItem(
                selected = false,
                onClick = onPairClicked,
                colors = itemColors,
                scale = itemScale,
                shape = itemShape,
                headlineContent = {
                    Text(
                        text = stringResource(R.string.tv_action_pair_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                supportingContent = {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Text(text = stringResource(R.string.tv_action_pair_summary))
                        Spacer(modifier = Modifier.height(6.dp))
                        // 高對比資訊盒清楚呈現本機位址與配對碼
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF14111B))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.tv_pair_badge_format,
                                    if (ipAddress.isNotEmpty()) ipAddress else "127.0.0.1",
                                    pairingPin,
                                    pinRemainingSec
                                ),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE9DDFF)
                            )
                        }
                    }
                },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.devices_other_48px),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 音量聲道區段標題
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.volume_streams_header),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD0BCFF),
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }

        // 音量聲道卡片清單
        itemsIndexed(volumes, key = { _, v -> v.stream }) { index, volume ->
            var isItemFocused by remember { mutableStateOf(false) }
            val isLocked = locks.containsKey(volume.stream)
            val displayVal = locks[volume.stream] ?: volume.value
            val streamIconRes = when (volume.stream) {
                AudioManager.STREAM_MUSIC -> R.drawable.ic_media
                AudioManager.STREAM_VOICE_CALL -> R.drawable.ic_call
                AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_RING -> R.drawable.ic_bell
                AudioManager.STREAM_ALARM -> R.drawable.ic_alarm
                else -> R.drawable.ic_media
            }

            val focusModifier = if (index == 0) {
                Modifier.focusRequester(volumeFocusRequester)
            } else {
                Modifier
            }

            ListItem(
                selected = isLocked,
                onClick = {
                    if (isLocked) {
                        if (!hideTvUnlockUi) {
                            onUnlockStream(volume.stream)
                        } else {
                            onLockAllClicked()
                        }
                    }
                },
                colors = itemColors,
                scale = itemScale,
                shape = itemShape,
                headlineContent = {
                    Text(
                        text = getStreamDisplayName(volume.stream, volume.name),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                supportingContent = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isLocked) {
                                    stringResource(R.string.stream_level_locked, displayVal, volume.max)
                                } else {
                                    stringResource(R.string.stream_level_current, displayVal, volume.max)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isItemFocused) Color(0xFF121017) else Color(0xFFD8D0E3)
                            )
                            Text(
                                text = if (isLocked) {
                                    if (!hideTvUnlockUi) {
                                        stringResource(R.string.stream_hint_unlock_click)
                                    } else {
                                        stringResource(R.string.stream_hint_locked)
                                    }
                                } else {
                                    stringResource(R.string.stream_hint_adjust)
                                },
                                fontSize = 11.sp,
                                fontWeight = if (isItemFocused) FontWeight.Bold else FontWeight.Normal,
                                color = if (isItemFocused) {
                                    if (isLocked) {
                                        if (!hideTvUnlockUi) Color(0xFF5A3E94) else Color(0xFF616161)
                                    } else {
                                        Color(0xFF5A3E94)
                                    }
                                } else {
                                    if (isLocked) {
                                        if (!hideTvUnlockUi) Color(0xFFD0BCFF) else Color(0xFF9E9E9E)
                                    } else {
                                        Color(0xFFA59DB2)
                                    }
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // 水平進度條
                        val progressFraction = if (volume.max > volume.min) {
                            ((displayVal - volume.min).toFloat() / (volume.max - volume.min).toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (isItemFocused) Color(0xFFDDD8E6) else Color(0xFF332D40))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progressFraction)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (isLocked) {
                                            if (isItemFocused) Color(0xFF616161) else Color(0xFF9E9E9E)
                                        } else {
                                            if (isItemFocused) Color(0xFF6750A4) else Color(0xFFD0BCFF)
                                        }
                                    )
                            )
                        }
                    }
                },
                leadingContent = {
                    Icon(
                        painter = painterResource(streamIconRes),
                        contentDescription = null,
                        tint = if (isItemFocused) {
                            Color(0xFF121017)
                        } else {
                            if (isLocked) Color(0xFF9E9E9E) else Color(0xFFE9DDFF)
                        },
                        modifier = Modifier.size(24.dp)
                    )
                },
                trailingContent = {
                    if (isLocked) {
                        Icon(
                            painter = painterResource(R.drawable.ic_lock),
                            contentDescription = null,
                            tint = if (isItemFocused) Color(0xFF616161) else Color(0xFF9E9E9E),
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Text(
                            text = "$displayVal",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (isItemFocused) Color(0xFF121017) else Color(0xFFF4EFFA)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(focusModifier)
                    .onFocusChanged { isItemFocused = it.isFocused }
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.key) {
                                Key.DirectionLeft -> {
                                    onAdjustVolume(volume.stream, -1)
                                    true
                                }
                                Key.DirectionRight -> {
                                    onAdjustVolume(volume.stream, 1)
                                    true
                                }
                                else -> false
                            }
                        } else {
                            false
                        }
                    }
            )
        }
    }
}
