package cn.edu.bjtu.mis.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.R
import cn.edu.bjtu.mis.data.repository.SessionRepository
import cn.edu.bjtu.mis.model.AutoLoginStatus
import cn.edu.bjtu.mis.ui.friendlyTimestamp
import cn.edu.bjtu.mis.ui.theme.AppUiStyle
import cn.edu.bjtu.mis.ui.theme.LocalAppDesign
import cn.edu.bjtu.mis.ui.theme.LocalAppUiStyle
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    sessionRepository: SessionRepository,
    onLoggedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isApple = LocalAppUiStyle.current == AppUiStyle.Apple
    val design = LocalAppDesign.current
    val largeFont = LocalConfiguration.current.fontScale >= 1.5f
    var loginName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var captchaText by remember { mutableStateOf("") }
    var captchaDataUrl by remember { mutableStateOf<String?>(null) }
    var fetchedAt by remember { mutableStateOf<String?>(null) }
    var showManualCaptcha by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refreshCaptcha() {
        captchaDataUrl = null
        fetchedAt = null
        captchaText = ""
        runCatching { sessionRepository.captcha() }
            .onSuccess {
                captchaDataUrl = it.imageDataUrl
                fetchedAt = it.fetchedAt
            }
            .onFailure { error = it.message ?: "验证码加载失败，请点击重试。" }
    }

    fun loadCaptcha() {
        scope.launch {
            busy = true
            error = null
            try {
                refreshCaptcha()
            } finally {
                busy = false
            }
        }
    }

    fun submitAutoLogin() {
        scope.launch {
            busy = true
            error = null
            runCatching { sessionRepository.loginAuto(loginName, password) }
                .onSuccess { result ->
                    when (result.status) {
                        AutoLoginStatus.Ready -> onLoggedIn()
                        AutoLoginStatus.ManualRequired -> {
                            showManualCaptcha = true
                            captchaDataUrl = result.captcha?.imageDataUrl
                            fetchedAt = result.captcha?.fetchedAt
                            captchaText = ""
                            error = result.message
                        }
                        AutoLoginStatus.AutoFailed -> error = result.message ?: "自动登录失败"
                    }
                }
                .onFailure { error = it.message ?: "自动登录失败" }
            busy = false
        }
    }

    fun submitManualLogin() {
        scope.launch {
            busy = true
            error = null
            runCatching { sessionRepository.login(loginName, password, captchaText) }
                .onSuccess { onLoggedIn() }
                .onFailure {
                    error = it.message ?: "登录失败"
                    showManualCaptcha = true
                    refreshCaptcha()
                }
            busy = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (isApple) {
                    Modifier.background(MaterialTheme.colorScheme.background)
                } else {
                    Modifier.background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    )
                },
            )
            .padding(if (isApple) design.pageHorizontalPadding else 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = if (isApple) 0.dp else 2.dp),
            border = if (isApple) {
                null
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            },
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.loading_mascot),
                        contentDescription = "BJTU MIS",
                        modifier = Modifier
                            .size(72.dp),
                    )
                    Text(
                        "BJTU MIS",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "校园信息本地采集与离线查看",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = loginName,
                    onValueChange = { loginName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("学号 / 工号") },
                    singleLine = true,
                    enabled = !busy,
                    shape = MaterialTheme.shapes.medium,
                    colors = loginTextFieldColors(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !busy,
                    shape = MaterialTheme.shapes.medium,
                    colors = loginTextFieldColors(),
                )
                if (showManualCaptcha) {
                    if (largeFont) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = captchaText,
                                onValueChange = { captchaText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("验证码") },
                                singleLine = true,
                                enabled = !busy,
                                shape = MaterialTheme.shapes.medium,
                                colors = loginTextFieldColors(),
                            )
                            CaptchaImage(
                                dataUrl = captchaDataUrl,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.shapes.medium,
                                    )
                                    .clickable(enabled = !busy) { loadCaptcha() },
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = captchaText,
                                onValueChange = { captchaText = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("验证码") },
                                singleLine = true,
                                enabled = !busy,
                                shape = MaterialTheme.shapes.medium,
                                colors = loginTextFieldColors(),
                            )
                            Spacer(Modifier.width(12.dp))
                            CaptchaImage(
                                dataUrl = captchaDataUrl,
                                modifier = Modifier
                                    .width(118.dp)
                                    .height(48.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.shapes.medium,
                                    )
                                    .clickable(enabled = !busy) { loadCaptcha() },
                            )
                        }
                    }
                    if (!fetchedAt.isNullOrBlank()) {
                        Text(
                            "验证码更新时间：${friendlyTimestamp(fetchedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (!error.isNullOrBlank()) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    enabled = !busy &&
                        loginName.isNotBlank() &&
                        password.isNotBlank() &&
                        (!showManualCaptcha || captchaText.isNotBlank()),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    onClick = {
                        if (showManualCaptcha) {
                            submitManualLogin()
                        } else {
                            submitAutoLogin()
                        }
                    },
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(if (showManualCaptcha) "提交验证码登录" else "自动登录")
                    }
                }
                if (showManualCaptcha) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        shape = MaterialTheme.shapes.medium,
                        onClick = { loadCaptcha() },
                    ) {
                        Text("刷新验证码")
                    }
                }
            }
        }
    }
}

@Composable
private fun loginTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    cursorColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
)

@Composable
private fun CaptchaImage(dataUrl: String?, modifier: Modifier = Modifier) {
    val bitmap = remember(dataUrl) {
        val base64 = dataUrl?.substringAfter("base64,", missingDelimiterValue = "").orEmpty()
        if (base64.isBlank()) null else runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "验证码", modifier = modifier)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("点击加载", style = MaterialTheme.typography.bodySmall)
        }
    }
}
