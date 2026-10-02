package ahut.wifiauth.android.network

import ahut.wifiauth.android.model.LoginResult
import ahut.wifiauth.android.model.OnlineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Test fixtures are fictional; the IP uses the TEST-NET-1 documentation range.
class EPortalClientTest {

    private val client = EPortalClient()

    @Test
    fun testBuildAuthUrl() {
        val url = client.buildAuthUrl(
            username = "test-student",
            password = "example&password",
            wifiIp = "192.0.2.10",
            v = 4792
        )

        assertTrue("URL 必须以基础网关地址开头", url.startsWith("http://10.255.255.154:801/eportal/portal/login?"))
        assertTrue("URL 必须包含 callback=dr1003", url.contains("callback=dr1003"))
        assertTrue("URL 必须包含 账号前缀 ,0,", url.contains("user_account=,0,test-student"))
        assertTrue("URL 必须包含 密码转义", url.contains("user_password=example%26password"))
        assertTrue("URL 必须包含 校园内网 IP", url.contains("wlan_user_ip=192.0.2.10"))
        assertTrue("URL 必须包含 v 参数", url.contains("v=4792"))
        assertTrue("URL 必须包含 终端类型", url.contains("terminal_type=1"))
        assertTrue("URL 必须包含 客户端版本", url.contains("jsVersion=4.1.3"))
    }

    @Test
    fun testParseJsonpResponseSuccess() {
        val rawResponse = """dr1003({"result":1,"msg":"Portal协议认证成功！"});"""
        val result = client.parseJsonpResponse(rawResponse)

        assertTrue(result is LoginResult.Success)
        val success = result as LoginResult.Success
        assertEquals("Portal协议认证成功！", success.message)
    }

    @Test
    fun testParseJsonpResponsePortalError() {
        val rawResponse = """dr1003({"result":0,"msg":"用户密码错误"});"""
        val result = client.parseJsonpResponse(rawResponse)

        assertTrue(result is LoginResult.PortalError)
        val error = result as LoginResult.PortalError
        assertEquals("用户密码错误", error.message)
        assertEquals(0, error.rawResult)
    }

    @Test
    fun testParseJsonpResponseWithoutSemicolon() {
        val rawResponse = """dr1003({"result":1,"msg":"认证成功"})"""
        val result = client.parseJsonpResponse(rawResponse)

        assertTrue(result is LoginResult.Success)
        assertEquals("认证成功", (result as LoginResult.Success).message)
    }

    @Test
    fun testParseJsonpResponseMalformed() {
        val rawResponse = """<html><body>Gateway Timeout</body></html>"""
        val result = client.parseJsonpResponse(rawResponse)

        assertTrue(result is LoginResult.ParseError)
    }

    @Test
    fun testParseChkStatusOnline() {
        val rawResponse = """dr1002({"result":1,"uid":"test-student","v4ip":"192.0.2.10","time":3600,"flow":1024});"""
        val status = client.parseChkStatusResponse(rawResponse)

        assertTrue("必须识别为 Online 状态", status is OnlineStatus.Online)
        val online = status as OnlineStatus.Online
        assertEquals("test-student", online.uid)
        assertEquals("192.0.2.10", online.ip)
        assertEquals(3600L, online.time)
        assertEquals(1024L, online.flow)
    }

    @Test
    fun testParseChkStatusOffline() {
        val rawResponse = """dr1002({"result":0});"""
        val status = client.parseChkStatusResponse(rawResponse)

        assertTrue("必须识别为 Offline 状态", status is OnlineStatus.Offline)
    }
}
