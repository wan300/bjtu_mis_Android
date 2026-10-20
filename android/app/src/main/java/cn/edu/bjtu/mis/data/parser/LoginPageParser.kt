package cn.edu.bjtu.mis.data.parser

import cn.edu.bjtu.mis.data.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI

internal data class CampusLoginPage(
    val loginUrl: String,
    val fields: Map<String, String>,
    val isSso: Boolean,
    val captchaUrl: String? = null,
    val sm2PublicKey: String? = null,
    val captchaRequired: Boolean = false,
    val errorMessage: String? = null,
)

internal fun hasCampusLoginForm(html: String): Boolean = Jsoup.parse(html).selectFirst(
    "form#loginForm, form:has(input[name=loginname]):has(input[name=password])",
) != null

internal fun parseCampusLoginPage(pageUrl: String, html: String): CampusLoginPage {
    val document = Jsoup.parse(html, pageUrl)
    val ssoForm = document.selectFirst("form#loginForm:has(input[name=username])")
    val form = ssoForm
        ?: document.selectFirst("form:has(input[name=loginname]):has(input[name=password])")
        ?: throw IOException("未能解析统一认证登录表单，请刷新后重试。")
    val loginUrl = form.absUrl("action").ifBlank { pageUrl }
    val page = URI(pageUrl)
    val action = URI(loginUrl)
    if (page.scheme != action.scheme || page.host != action.host || page.port != action.port ||
        action.rawUserInfo != null || action.path.trimEnd('/') !in listOf("/auth/login", "/sso/login")
    ) {
        throw IOException("统一认证登录地址不受信任，已停止提交。")
    }
    val fields = form.select("input[type=hidden], input:not([type])")
        .filter { it.hasAttr("name") }
        .associate { it.attr("name") to it.attr("value") }
    fun required(name: String): String = fields[name]?.takeIf { it.isNotBlank() }
        ?: throw IOException("登录表单缺少字段 $name，请刷新后重试。")

    if (ssoForm == null) {
        required("csrfmiddlewaretoken")
        required("captcha_0")
        val captcha = form.selectFirst("img.captcha, img[alt=captcha]")
            ?: document.selectFirst("img.captcha, img[alt=captcha]")
            ?: throw IOException("未找到验证码图片。")
        val captchaUrl = captcha.absUrl("src").takeIf { it.isNotBlank() }
            ?: throw IOException("验证码图片地址为空。")
        val image = URI(captchaUrl)
        if (image.scheme != page.scheme || image.host != page.host || image.port != page.port) {
            throw IOException("验证码图片地址不受信任。")
        }
        return CampusLoginPage(loginUrl, fields, isSso = false, captchaUrl = captchaUrl)
    }

    required("flowId")
    val scripts = document.select("script:not([src])").joinToString("\n") { it.data() }
    val config = loginScriptObject(scripts, "ssoConfig")
        ?: throw IOException("统一认证配置缺失，请刷新后重试。")
    val sm2 = config["sm2"] as? JsonObject
    if (sm2?.get("enabled")?.jsonPrimitive?.booleanOrNull != true) {
        throw IOException("统一认证密码加密配置不可用，已停止提交。")
    }
    val publicKey = sm2["publicKey"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw IOException("统一认证密码加密公钥缺失。")
    val error = loginScriptObject(scripts, "error")
    val code = error?.get("code")?.jsonPrimitive?.contentOrNull
    val errorData = error?.get("data") as? JsonObject
    val captchaType = errorData?.get("captchaType")?.jsonPrimitive?.contentOrNull
    if (captchaType != null && captchaType != "penggle.image") {
        throw IOException("统一认证需要交互验证，当前 App 暂不支持此验证方式，请使用学校统一身份认证网页。")
    }
    if (code == "40701" || Regex("var\\s+(?:mfaType|pageName)\\s*=\\s*\"(?:mfa|select|bind)[^\"]*\"").containsMatchIn(scripts)) {
        throw IOException("统一认证需要二次验证，当前 App 暂不支持此验证方式，请使用学校统一身份认证网页。")
    }
    val errorMessage = when (code) {
        null, "20000" -> null
        "40400", "40401", "40402" -> "用户名或密码错误，请检查后重试。"
        "40700" -> "验证码校验未通过，请刷新后重试。"
        "40200", "40201", "40202", "40407" -> "登录验证已过期，请刷新后重试。"
        "30000", "40612" -> "登录尝试过于频繁，请稍后重试。"
        else -> "统一认证未完成（错误码 $code），请检查账号状态或稍后重试。"
    }
    return CampusLoginPage(
        loginUrl, fields, isSso = true, sm2PublicKey = publicKey,
        captchaRequired = captchaType == "penggle.image", errorMessage = errorMessage,
    )
}

private fun loginScriptObject(scripts: String, name: String): JsonObject? {
    val assignment = Regex("(?:var|let|const)\\s+$name\\s*=\\s*").find(scripts) ?: return null
    val start = assignment.range.last + 1
    if (scripts.getOrNull(start) != '{') return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until scripts.length) {
        val character = scripts[index]
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            when (character) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching {
                            AppJson.parseToJsonElement(scripts.substring(start, index + 1)) as? JsonObject
                        }.getOrNull()
                    }
                }
            }
        }
    }
    return null
}
