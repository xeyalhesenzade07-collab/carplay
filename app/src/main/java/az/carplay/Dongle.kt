package az.carplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Carlinkit tipli CarPlay adapterinin USB protokolu.
 * Mənbə: açıq mənbəli node-carplay layihəsi (tərs mühəndislik).
 * Firmware-dən asılı olaraq fərqlər ola bilər — öz adapterinizdə yoxlayın.
 *
 * Hər mesaj = 16 baytlıq başlıq + məlumat (payload). Hamısı little-endian.
 * Başlıq: magic(0x55AA55AA) | uzunluq | tip | ~tip
 */
object Dongle {
    const val VENDOR_ID = 0x1314
    val PRODUCT_IDS = setOf(0x1520, 0x1521)

    const val MAGIC = 0x55AA55AA.toInt()
    const val HEADER_SIZE = 16

    // Mesaj tipləri
    const val OPEN = 0x01
    const val PLUGGED = 0x02
    const val PHASE = 0x03
    const val UNPLUGGED = 0x04
    const val TOUCH = 0x05
    const val VIDEO = 0x06
    const val AUDIO = 0x07
    const val COMMAND = 0x08
    const val HEARTBEAT = 0xAA

    // Toxunuş hərəkətləri
    const val TOUCH_DOWN = 14
    const val TOUCH_MOVE = 15
    const val TOUCH_UP = 16

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    fun message(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val b = le(HEADER_SIZE + payload.size)
        b.putInt(MAGIC)
        b.putInt(payload.size)
        b.putInt(type)
        b.putInt(type.inv())
        b.put(payload)
        return b.array()
    }

    /** Sessiyanı açır: ekran ölçüsü, FPS və s. */
    fun open(width: Int, height: Int, fps: Int = 30): ByteArray {
        val p = le(28)
        p.putInt(width)
        p.putInt(height)
        p.putInt(fps)
        p.putInt(5)       // format
        p.putInt(49152)   // packetMax
        p.putInt(2)       // iBox versiyası
        p.putInt(2)       // phone work mode
        return message(OPEN, p.array())
    }

    fun heartbeat() = message(HEARTBEAT)

    /** x, y — ekranda piksel; adapter 0..10000 aralığı gözləyir. */
    fun touch(action: Int, x: Float, y: Float, w: Int, h: Int): ByteArray {
        val p = le(16)
        p.putInt(action)
        p.putInt((x / w * 10000).toInt().coerceIn(0, 10000))
        p.putInt((y / h * 10000).toInt().coerceIn(0, 10000))
        p.putInt(0)
        return message(TOUCH, p.array())
    }

    data class Header(val length: Int, val type: Int)

    fun parseHeader(buf: ByteArray): Header? {
        val b = ByteBuffer.wrap(buf, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != MAGIC) return null
        val len = b.int
        val type = b.int
        if (b.int != type.inv()) return null
        return Header(len, type)
    }
}
