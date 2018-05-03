package io.ktor.network.tls

import io.ktor.network.tls.extensions.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.io.core.*
import java.security.*
import java.security.interfaces.*
import java.security.spec.*
import javax.crypto.*

internal suspend fun ByteWriteChannel.writeRecord(record: TLSRecord) = with(record) {
    writeByte(type.code.toByte())
    writeShort(version.code.toShort())
    writeShort(packet.remaining.toShort())
    writePacket(packet)
    flush()
}

internal fun BytePacketBuilder.writeTLSHandshakeType(type: TLSHandshakeType, length: Int) {
    if (length > 0xffffff) throw TLSException("TLS handshake size limit exceeded: $length")
    val v = (type.code shl 24) or length
    writeInt(v)
}

internal fun BytePacketBuilder.writeTLSClientHello(
    version: TLSVersion,
    suites: List<CipherSuite>,
    random: ByteArray,
    sessionId: ByteArray,
    serverName: String? = null
) {
    writeShort(version.code.toShort())
    writeFully(random)

    val sessionIdLength = sessionId.size
    if (sessionIdLength < 0 || sessionIdLength > 0xff || sessionIdLength > sessionId.size) throw TLSException(
        "Illegal sessionIdLength"
    )

    writeByte(sessionIdLength.toByte())
    writeFully(sessionId, 0, sessionIdLength)

    writeShort((suites.size * 2).toShort())
    for (suite in suites) {
        writeShort(suite.code)
    }

    // compression is always null
    writeByte(1)
    writeByte(0)

    val extensions = ArrayList<ByteReadPacket>()
    extensions += buildSignatureAlgorithmsExtension()
    extensions += buildECCurvesExtension()
    extensions += buildECPointFormatExtension()

    serverName?.let { name ->
        extensions += buildServerNameExtension(name)
    }

    writeShort(extensions.sumBy { it.remaining }.toShort())
    for (e in extensions) {
        writePacket(e)
    }
}

internal fun BytePacketBuilder.writeEncryptedPreMasterSecret(
    preSecret: ByteArray,
    publicKey: PublicKey,
    random: SecureRandom
) {
    require(preSecret.size == 48)

    val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")!!
    rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey, random)
    val encryptedSecret = rsaCipher.doFinal(preSecret)

    if (encryptedSecret.size > 0xffff) throw TLSException("Encrypted premaster secret is too long")

    writeShort(encryptedSecret.size.toShort())
    writeFully(encryptedSecret)
}

internal fun finished(digest: ByteArray, secretKey: SecretKey) = buildPacket {
    val prf = PRF(secretKey, CLIENT_FINISHED_LABEL, digest, 12)
    writeFully(prf)
}

internal fun serverFinished(handshakeHash: ByteArray, secretKey: SecretKey, length: Int = 12): ByteArray =
    PRF(secretKey, SERVER_FINISHED_LABEL, handshakeHash, length)

internal fun BytePacketBuilder.writePublicKeyUncompressed(key: PublicKey) = when (key) {
    is ECPublicKey -> {
        val fieldSize = key.params.curve.field.fieldSize
        writeECPoint(key.w, fieldSize)
    }
    else -> throw TLSException("Unsupported public key type: $key")
}

internal fun BytePacketBuilder.writeECPoint(point: ECPoint, fieldSize: Int) {
    val pointData = buildPacket {
        writeByte(4) // 4 - uncompressed
        writeAligned(point.affineX.toByteArray(), fieldSize)
        writeAligned(point.affineY.toByteArray(), fieldSize)
    }

    writeByte(pointData.remaining.toByte())
    writePacket(pointData)
}

private fun buildSignatureAlgorithmsExtension(
    algorithms: List<HashAndSign> = SupportedSignatureAlgorithms
): ByteReadPacket = buildPacket {
    writeShort(TLSExtensionType.SIGNATURE_ALGORITHMS.code) // signature_algorithms extension

    val size = algorithms.size
    writeShort((2 + size * 2).toShort()) // length in bytes
    writeShort((size * 2).toShort()) // length in bytes

    algorithms.forEach {
        writeByte(it.hash.code)
        writeByte(it.sign.code)
    }
}

private const val MAX_SERVER_NAME_LENGTH: Int = Short.MAX_VALUE - 5
private fun buildServerNameExtension(name: String): ByteReadPacket = buildPacket {
    require(name.length < MAX_SERVER_NAME_LENGTH) {
        "Server name length limit exceeded: at most $MAX_SERVER_NAME_LENGTH characters allowed"
    }

    writeShort(TLSExtensionType.SERVER_NAME.code) // server_name
    writeShort((name.length + 2 + 1 + 2).toShort()) // length
    writeShort((name.length + 2 + 1).toShort()) // list length
    writeByte(0) // type: host_name
    writeShort(name.length.toShort()) // name length
    writeStringUtf8(name)
}

private const val MAX_CURVES_QUANTITY: Int = Short.MAX_VALUE / 2 - 1

private fun buildECCurvesExtension(curves: List<NamedCurve> = SupportedNamedCurves): ByteReadPacket = buildPacket {
    require(curves.size <= MAX_CURVES_QUANTITY) {
        "Too many named curves provided: at most $MAX_CURVES_QUANTITY could be provided"
    }

    writeShort(TLSExtensionType.ELLIPTIC_CURVES.code)
    val size = curves.size * 2

    writeShort((2 + size).toShort()) // extension length
    writeShort(size.toShort()) // list length

    curves.forEach {
        writeShort(it.code)
    }
}

private fun buildECPointFormatExtension(
    formats: List<PointFormat> = SupportedPointFormats
): ByteReadPacket = buildPacket {
    writeShort(TLSExtensionType.EC_POINT_FORMAT.code)

    val size = formats.size
    writeShort((1 + size).toShort()) // extension length

    writeByte(size.toByte()) // list length
    formats.forEach {
        writeByte(it.code)
    }
}

private fun BytePacketBuilder.writeAligned(src: ByteArray, fieldSize: Int) {
    val expectedSize = (fieldSize + 7) ushr 3
    val index = src.indexOfFirst { it != 0.toByte() }
    val padding = expectedSize - (src.size - index)

    if (padding > 0) writeFully(ByteArray(padding))
    writeFully(src, index, src.size - index)
}
