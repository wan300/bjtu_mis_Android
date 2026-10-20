package cn.edu.bjtu.mis.data.security

import org.bouncycastle.asn1.gm.GMNamedCurves
import org.bouncycastle.crypto.engines.SM2Engine
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.ParametersWithRandom
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

internal fun encryptSsoPassword(password: String, publicKey: String): String {
    val plaintext = password.toByteArray(Charsets.UTF_8)
    try {
        val curve = GMNamedCurves.getByName("sm2p256v1")
        val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
        val keyBytes = Base64.getDecoder().decode(publicKey)
        require(keyBytes.size == 65 && keyBytes[0] == 4.toByte())
        val key = ECPublicKeyParameters(curve.curve.decodePoint(keyBytes), domain)
        val engine = SM2Engine(SM2Engine.Mode.C1C3C2)
        engine.init(true, ParametersWithRandom(key, SecureRandom()))
        val encrypted = engine.processBlock(plaintext, 0, plaintext.size)
        // The school's sm2.encrypt uses Base64 C1C3C2 without the uncompressed-point 04 prefix.
        return Base64.getEncoder().encodeToString(encrypted.copyOfRange(1, encrypted.size))
    } catch (error: Exception) {
        throw IOException("统一认证密码加密失败，请刷新登录页后重试。", error)
    } finally {
        plaintext.fill(0)
    }
}
