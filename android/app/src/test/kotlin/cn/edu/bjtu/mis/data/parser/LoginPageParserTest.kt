package cn.edu.bjtu.mis.data.parser

import cn.edu.bjtu.mis.data.provider.isBjtuCasLoginUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LoginPageParserTest {
    private val fixture = javaClass.classLoader!!.getResource("fixtures/sso_login.html")!!.readText()
    private val url = "https://sso.bjtu.edu.cn/o/authorize/?state=sample"

    @Test
    fun parsesSsoFormWithoutRequiringOldCaptchaFields() {
        val page = parseCampusLoginPage(url, fixture)
        assertTrue(page.isSso)
        assertFalse(page.captchaRequired)
        assertEquals("https://sso.bjtu.edu.cn/sso/login", page.loginUrl)
        assertEquals("test-flow", page.fields["flowId"])
    }

    @Test
    fun acceptsMultilineJsonAndEscapedBraces() {
        val html = fixture.replace("{\"sm2\":", "{\n\"description\":\"brace } and \\\"quote\\\"\",\n\"sm2\":")
        assertTrue(parseCampusLoginPage(url, html).isSso)
    }

    @Test
    fun rejectsMissingFlowAndEncryptionConfiguration() {
        for (html in listOf(
            fixture.replace("name=\"flowId\"", "name=\"removed\""),
            fixture.replace("\"enabled\":true", "\"enabled\":false"),
            fixture.replace("TEST_SM2_PUBLIC_KEY", ""),
        )) {
            assertThrows(IOException::class.java) { parseCampusLoginPage(url, html) }
        }
    }

    @Test
    fun rejectsOffOriginOrUnexpectedPasswordSubmission() {
        for (action in listOf("https://other.example/sso/login", "http://sso.bjtu.edu.cn/sso/login", "/unexpected", "https://sso.bjtu.edu.cn:8443/sso/login")) {
            assertThrows(IOException::class.java) {
                parseCampusLoginPage(url, fixture.replace("action=\"/sso/login\"", "action=\"$action\""))
            }
        }
    }

    @Test
    fun rejectsUnsupportedChallengeInsteadOfPretendingLoggedIn() {
        val html = fixture.replace("var error = null;", "var error = {\"code\":40700,\"data\":{\"captchaType\":\"aj.blockPuzzle\"}};")
        assertThrows(IOException::class.java) { parseCampusLoginPage(url, html) }
    }

    @Test
    fun recognizesSsoAndCasAuthorizationUrlsButNotUnrelatedHosts() {
        assertTrue(isBjtuCasLoginUrl(url))
        assertTrue(isBjtuCasLoginUrl("https://cas.bjtu.edu.cn/o/authorize/"))
        assertTrue(isBjtuCasLoginUrl("https://sso.bjtu.edu.cn/sso/login"))
        assertTrue(isBjtuCasLoginUrl("https://cas.bjtu.edu.cn/auth/login/"))
        assertFalse(isBjtuCasLoginUrl("https://unrelated.example/o/authorize/"))
        assertFalse(isBjtuCasLoginUrl("https://mis.bjtu.edu.cn/home/"))
        assertTrue(hasCampusLoginForm(fixture))
        assertFalse(hasCampusLoginForm("<html>home ready</html>"))
    }
}
