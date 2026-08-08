package kittoku.mvc.client

import android.os.Build
import kittoku.mvc.ControlMessage
import com.mlmvpn.scanner.R
import kittoku.mvc.Result
import kittoku.mvc.SharedBridge
import kittoku.mvc.Where
import kittoku.mvc.cipher.hashSha0
import kittoku.mvc.debug.assertAlways
import kittoku.mvc.extension.nextBytes
import kittoku.mvc.teminal.CHACHA20_POLY1305_KEY_SIZE
import kittoku.mvc.teminal.UDP_CIPHER_ALGORITHM
import kittoku.mvc.unit.HttpMessage
import kittoku.mvc.unit.IPv4_ADDRESS_SIZE
import kittoku.mvc.unit.property.COMPATIBLE_BUILD
import kittoku.mvc.unit.property.COMPATIBLE_VERSION
import kittoku.mvc.unit.property.PropertyPack
import kittoku.mvc.unit.property.SEP_AUTH_TYPE
import kittoku.mvc.unit.property.SEP_BUILD
import kittoku.mvc.unit.property.SEP_CLIENT_BUILD
import kittoku.mvc.unit.property.SEP_CLIENT_HOSTNAME
import kittoku.mvc.unit.property.SEP_CLIENT_ID
import kittoku.mvc.unit.property.SEP_CLIENT_IP_ADDRESS
import kittoku.mvc.unit.property.SEP_CLIENT_OS_NAME
import kittoku.mvc.unit.property.SEP_CLIENT_OS_VER
import kittoku.mvc.unit.property.SEP_CLIENT_PORT
import kittoku.mvc.unit.property.SEP_CLIENT_PRODUCT_BUILD
import kittoku.mvc.unit.property.SEP_CLIENT_PRODUCT_NAME
import kittoku.mvc.unit.property.SEP_CLIENT_PRODUCT_VER
import kittoku.mvc.unit.property.SEP_CLIENT_STR
import kittoku.mvc.unit.property.SEP_CLIENT_VER
import kittoku.mvc.unit.property.SEP_ERROR
import kittoku.mvc.unit.property.SEP_HALF_CONNECTION
import kittoku.mvc.unit.property.SEP_HELLO
import kittoku.mvc.unit.property.SEP_HUB_NAME
import kittoku.mvc.unit.property.SEP_MAX_CONNECTION
import kittoku.mvc.unit.property.SEP_METHOD
import kittoku.mvc.unit.property.SEP_PEN_CORE
import kittoku.mvc.unit.property.SEP_PROTOCOL
import kittoku.mvc.unit.property.SEP_PROXY_IP_ADDRESS
import kittoku.mvc.unit.property.SEP_PROXY_PORT
import kittoku.mvc.unit.property.SEP_RANDOM
import kittoku.mvc.unit.property.SEP_SECURE_PASSWORD
import kittoku.mvc.unit.property.SEP_SERVER_HOSTNAME
import kittoku.mvc.unit.property.SEP_SERVER_IP_ADDRESS
import kittoku.mvc.unit.property.SEP_SERVER_PORT2
import kittoku.mvc.unit.property.SEP_SERVER_PRODUCT_BUILD
import kittoku.mvc.unit.property.SEP_SERVER_PRODUCT_NAME
import kittoku.mvc.unit.property.SEP_SERVER_PRODUCT_VER
import kittoku.mvc.unit.property.SEP_UDP_CLIENT_COOKIE
import kittoku.mvc.unit.property.SEP_UDP_CLIENT_IP
import kittoku.mvc.unit.property.SEP_UDP_CLIENT_KEY_V2
import kittoku.mvc.unit.property.SEP_UDP_CLIENT_PORT
import kittoku.mvc.unit.property.SEP_UDP_ENABLE_FAST_DISCONNECT_DETECT
import kittoku.mvc.unit.property.SEP_UDP_MAX_VERSION
import kittoku.mvc.unit.property.SEP_UDP_SERVER_COOKIE
import kittoku.mvc.unit.property.SEP_UDP_SERVER_IP
import kittoku.mvc.unit.property.SEP_UDP_SERVER_KEY_V2
import kittoku.mvc.unit.property.SEP_UDP_SERVER_PORT
import kittoku.mvc.unit.property.SEP_UDP_SUPPORT_FAST_DISCONNECT_DETECT
import kittoku.mvc.unit.property.SEP_UDP_USE_ENCRYPTION
import kittoku.mvc.unit.property.SEP_UDP_VERSION
import kittoku.mvc.unit.property.SEP_UNIQUE_ID_CAMEL
import kittoku.mvc.unit.property.SEP_UNIQUE_ID_SNAKE
import kittoku.mvc.unit.property.SEP_USERNAME
import kittoku.mvc.unit.property.SEP_USE_COMPRESS
import kittoku.mvc.unit.property.SEP_USE_ENCRYPT
import kittoku.mvc.unit.property.SEP_USE_UDP_ACCELERATION
import kittoku.mvc.unit.property.SEP_VERSION
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.nio.ByteBuffer
import javax.crypto.spec.SecretKeySpec


private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 6.3; WOW64; rv:29.0) Gecko/20100101 Firefox/29.0"
private const val HTTP_KEEP_ALIVE = "timeout=15; max=19"

private const val HTTP_200_HEADER = "HTTP/1.1 200 OK"
private const val HTTP_DETECT_TAG = "9C37197CA7C2428388C2E6E59B829B30"
private val HTTP_DETECT_BODY = """<!DOCTYPE HTML PUBLIC "-//IETF//DTD HTML 2.0//EN">
    |<HTML><HEAD>
    |<TITLE>403 Forbidden</TITLE>
    |</HEAD><BODY>
    |<H1>Forbidden</H1>
    |You don't have permission to access """.trimMargin().replace("\n", "\r\n")
private const val UDP_ACCELERATION_V2_KEY_SIZE = 128
internal const val SOFTETHER_NEGOTIATION_TIMEOUT: Long = 30_000

internal class SoftEtherClient(private val bridge: SharedBridge) {
    internal val mailbox = Channel<HttpMessage>(Channel.BUFFERED)
    private var jobNegotiation: Job? = null
    private var receivedPack: PropertyPack? = null

    internal fun launchJobNegotiation() {
        jobNegotiation = bridge.scope.launch(bridge.handler) {
            uploadWatermark()
            uploadProperties()

            bridge.httpRequestChannel.send(false)
            bridge.controlMailbox.send(ControlMessage(Where.SOFTETHER, Result.PROCEEDED))
        }
    }

    private suspend fun checkSoftEtherServer() { // reserved for future use
        val request = HttpMessage().also {
            it.startLine = "GET / HTTP/1.1"
            it.fields["X-VPN"] = "1"
            it.fields["Host"] = bridge.serverHostname
            it.fields["Keep-Alive"] = HTTP_KEEP_ALIVE
            it.fields["Connection"] = "Keep-Alive"
            it.fields["Accept-Language"] = "ja"
            it.fields["User-Agent"] = DEFAULT_USER_AGENT
            it.fields["Pragma"] = "no-cache"
            it.fields["Cache-Control"] = "no-cache"
        }

        bridge.tcpTerminal!!.sendHttpMessage(request)
        bridge.httpRequestChannel.send(true)
        val response = mailbox.receive()

        val bodyText = response.body?.toString(Charsets.US_ASCII) ?: ""

        assertAlways(bodyText.contains(HTTP_DETECT_TAG) || bodyText.startsWith(HTTP_DETECT_BODY))
    }

    private suspend fun uploadWatermark() {
        val randomSize = bridge.random.nextInt(2000)
        val bodySize = WATERMARK.size + randomSize

        val request = HttpMessage().also {
            it.startLine = "POST /vpnsvc/connect.cgi HTTP/1.1"
            it.fields["Host"] = bridge.serverHostname
            it.fields["Content-Type"] = "image/jpeg"
            it.fields["Content-Length"] = bodySize.toString()
            it.fields["Connection"] = "Keep-Alive"
        }


        request.body = ByteBuffer.allocate(bodySize).let {
            it.put(WATERMARK)
            it.put(bridge.random.nextBytes(randomSize))
            it.array()
        }

        bridge.tcpTerminal!!.sendHttpMessage(request)
        bridge.httpRequestChannel.send(true)
        val response = mailbox.receive()

        receivedPack = PropertyPack().also {
            it.read(ByteBuffer.wrap(response.body!!))
        }

        assertAlways(response.startLine == HTTP_200_HEADER)
        assertAlways(receivedPack!!.intProperties[SEP_ERROR] == null)
        assertAlways(receivedPack!!.bytesProperties[SEP_RANDOM] != null)
    }

    private fun calcSecurePassword(): ByteArray {
        val password = bridge.clientPassword.toByteArray(Charsets.US_ASCII)
        val uppercaseUsername = bridge.clientUsername.uppercase().toByteArray(Charsets.US_ASCII)

        val hashedPassword = hashSha0(password + uppercaseUsername)

        return hashSha0(hashedPassword + receivedPack!!.bytesProperties[SEP_RANDOM]!!)
    }

    private fun preparePropertyPack(): ByteArray {
        val pack = PropertyPack()
        val appName = bridge.service.getText(R.string.app_name).toString()

        pack.intProperties[SEP_BUILD] = COMPATIBLE_BUILD
        pack.intProperties[SEP_VERSION] = COMPATIBLE_VERSION
        pack.bytesProperties[SEP_UNIQUE_ID_CAMEL] = bridge.random.nextBytes(16)
        pack.bytesProperties[SEP_UNIQUE_ID_SNAKE] = bridge.random.nextBytes(20) // pseudo implementation
        pack.intProperties[SEP_CLIENT_BUILD] = COMPATIBLE_BUILD
        pack.intProperties[SEP_CLIENT_ID] = 0
        pack.asciiProperties[SEP_CLIENT_STR] = appName
        pack.intProperties[SEP_CLIENT_VER] = COMPATIBLE_VERSION
        pack.asciiProperties[SEP_CLIENT_HOSTNAME] = bridge.socket.localAddress.hostName
        pack.addressProperties[SEP_CLIENT_IP_ADDRESS] = bridge.socket.localAddress.address.copyOf()
        pack.asciiProperties[SEP_CLIENT_OS_NAME] = "Android"
        pack.asciiProperties[SEP_CLIENT_OS_VER] = Build.VERSION.RELEASE
        pack.intProperties[SEP_CLIENT_PORT] = bridge.socket.localPort
        pack.intProperties[SEP_CLIENT_PRODUCT_BUILD] = COMPATIBLE_BUILD
        pack.asciiProperties[SEP_CLIENT_PRODUCT_NAME] = appName
        pack.intProperties[SEP_CLIENT_PRODUCT_VER] = COMPATIBLE_VERSION
        pack.asciiProperties[SEP_HELLO] = appName

        pack.intProperties[SEP_PROXY_PORT] = 0
        pack.addressProperties[SEP_PROXY_IP_ADDRESS] = ByteArray(IPv4_ADDRESS_SIZE)

        pack.asciiProperties[SEP_SERVER_HOSTNAME] = bridge.socket.inetAddress.hostName
        pack.addressProperties[SEP_SERVER_IP_ADDRESS] = bridge.socket.inetAddress.address.copyOf()
        pack.intProperties[SEP_SERVER_PORT2] = bridge.socket.port
        pack.intProperties[SEP_SERVER_PRODUCT_BUILD] = receivedPack!!.intProperties[SEP_BUILD] ?: 0
        pack.asciiProperties[SEP_SERVER_PRODUCT_NAME] = receivedPack!!.asciiProperties[SEP_HELLO] ?: ""
        pack.intProperties[SEP_SERVER_PRODUCT_VER] = receivedPack!!.intProperties[SEP_VERSION] ?: 0

        pack.asciiProperties[SEP_METHOD] = "login"
        pack.intProperties[SEP_AUTH_TYPE] = 1
        pack.asciiProperties[SEP_USERNAME] = bridge.clientUsername
        pack.intProperties[SEP_PROTOCOL] = 0
        pack.asciiProperties[SEP_HUB_NAME] = bridge.serverHubName
        pack.booleanProperties[SEP_USE_ENCRYPT] = true
        pack.booleanProperties[SEP_USE_COMPRESS] = false
        pack.intProperties[SEP_MAX_CONNECTION] = 1
        pack.intProperties[SEP_HALF_CONNECTION] = 0
        pack.bytesProperties[SEP_SECURE_PASSWORD] = calcSecurePassword()
        pack.bytesProperties[SEP_PEN_CORE] = bridge.random.nextBytes(bridge.random.nextInt(1000))

        bridge.udpAccelerationConfig?.also { config ->
            pack.booleanProperties[SEP_USE_UDP_ACCELERATION] = true
            pack.intProperties[SEP_UDP_VERSION] = 2
            pack.intProperties[SEP_UDP_MAX_VERSION] = 2
            pack.addressProperties[SEP_UDP_CLIENT_IP] = config.clientReportedAddress.address.copyOf()
            pack.intProperties[SEP_UDP_CLIENT_PORT] = config.clientReportedPort
            pack.booleanProperties[SEP_UDP_SUPPORT_FAST_DISCONNECT_DETECT] = true
            pack.bytesProperties[SEP_UDP_CLIENT_KEY_V2] = bridge.random.nextBytes(UDP_ACCELERATION_V2_KEY_SIZE).also {
                config.clientKey = SecretKeySpec(it.copyOf(CHACHA20_POLY1305_KEY_SIZE), UDP_CIPHER_ALGORITHM)
            }
        }

        val buffer = ByteBuffer.allocate(pack.length)
        pack.write(buffer)

        return buffer.array()
    }

    private suspend fun uploadProperties() {
        val request = HttpMessage().also {
            it.startLine = "POST /vpnsvc/vpn.cgi HTTP/1.1"
            it.body = preparePropertyPack()
            it.fields["Host"] = bridge.serverHostname
            it.fields["Content-Type"] = "application/octet-stream"
            it.fields["Content-Length"] = it.body!!.size.toString()
            it.fields["Connection"] = "Keep-Alive"
            it.fields["Keep-Alive"] = HTTP_KEEP_ALIVE
        }

        bridge.tcpTerminal!!.sendHttpMessage(request)
        bridge.httpRequestChannel.send(true)
        val response = mailbox.receive()

        val pack = PropertyPack().also {
            it.read(ByteBuffer.wrap(response.body!!))
        }

        assertAlways(response.startLine == HTTP_200_HEADER)
        assertAlways(pack.intProperties[SEP_ERROR] == null)

        bridge.udpAccelerationConfig?.also { config ->
            // notify disabled denied
            assertAlways(pack.intProperties[SEP_UDP_VERSION] == 2)
            assertAlways(pack.booleanProperties[SEP_UDP_USE_ENCRYPTION] == true)
            assertAlways(pack.booleanProperties[SEP_UDP_ENABLE_FAST_DISCONNECT_DETECT] == true)

            pack.intProperties[SEP_UDP_CLIENT_COOKIE]?.also {
                config.clientCookie = it
            } ?: throw AssertionError()

            pack.addressProperties[SEP_UDP_SERVER_IP]?.also {
                config.serverReportedAddress = Inet4Address.getByAddress(it) as Inet4Address
            } ?: throw AssertionError()

            pack.intProperties[SEP_UDP_SERVER_PORT]?.also {
                config.serverReportedPort = it
            } ?: throw AssertionError()

            pack.intProperties[SEP_UDP_SERVER_COOKIE]?.also {
                config.serverCookie = it
            } ?: throw AssertionError()

            pack.bytesProperties[SEP_UDP_SERVER_KEY_V2]?.also {
                config.serverKey = SecretKeySpec(it.copyOf(CHACHA20_POLY1305_KEY_SIZE), UDP_CIPHER_ALGORITHM)
            } ?: throw AssertionError()
        }
    }

    internal fun cancel() {
        jobNegotiation?.cancel()
        mailbox.close()
    }
}