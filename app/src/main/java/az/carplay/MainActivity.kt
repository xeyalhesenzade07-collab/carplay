package az.carplay

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Sadə CarPlay qəbuledicisi:
 * 1) Adapteri USB-də tapır və icazə istəyir
 * 2) OPEN mesajı göndərir, hər 2 saniyədən bir HEARTBEAT
 * 3) Gələn VIDEO mesajlarını MediaCodec ilə ekrana çıxarır
 * 4) Ekrana toxunuşları adapterə göndərir
 */
class MainActivity : Activity(), SurfaceHolder.Callback {

    private val TAG = "CarPlayApk"
    private val ACTION_PERMISSION = "az.carplay.USB_PERMISSION"

    private lateinit var usb: UsbManager
    private lateinit var surfaceView: SurfaceView
    private var conn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var decoder: MediaCodec? = null
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
        surfaceView.holder.addCallback(this)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
    }

    // ---------- Ekran hazır olanda adapteri axtar ----------
    override fun surfaceCreated(holder: SurfaceHolder) = findDongle()
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) = stop()

    private fun findDongle() {
        val device = usb.deviceList.values.firstOrNull {
            it.vendorId == Dongle.VENDOR_ID && it.productId in Dongle.PRODUCT_IDS
        }
        if (device == null) {
            Log.w(TAG, "Adapter tapılmadı. Qoşulan cihazlar: " +
                usb.deviceList.values.joinToString { "%04x:%04x".format(it.vendorId, it.productId) })
            return
        }
        if (usb.hasPermission(device)) open(device) else requestPermission(device)
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0,
            Intent(ACTION_PERMISSION).setPackage(packageName), flags)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                unregisterReceiver(this)
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(device)
                else Log.e(TAG, "USB icazəsi verilmədi")
            }
        }
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(receiver, IntentFilter(ACTION_PERMISSION), RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(receiver, IntentFilter(ACTION_PERMISSION))
        usb.requestPermission(device, pi)
    }

    // ---------- USB əlaqəsi ----------
    private fun open(device: UsbDevice) {
        val iface = device.getInterface(0)
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
        }
        conn = usb.openDevice(device)?.also { it.claimInterface(iface, true) }
        if (conn == null || epIn == null || epOut == null) {
            Log.e(TAG, "Adapter açılmadı"); return
        }

        val w = surfaceView.width
        val h = surfaceView.height
        startDecoder(w, h)

        running = true
        send(Dongle.open(w, h))
        thread(name = "heartbeat") {
            while (running) { send(Dongle.heartbeat()); Thread.sleep(2000) }
        }
        thread(name = "reader") { readLoop() }
    }

    private fun send(data: ByteArray) {
        conn?.bulkTransfer(epOut, data, data.size, 1000)
    }

    /** Tam `n` bayt oxuyur (USB paketləri hissə-hissə gələ bilər). */
    private fun readFully(n: Int): ByteArray? {
        val out = ByteArray(n)
        var off = 0
        val chunk = ByteArray(16384)
        while (off < n && running) {
            val want = minOf(chunk.size, n - off)
            val got = conn?.bulkTransfer(epIn, chunk, want, 3000) ?: return null
            if (got < 0) continue           // timeout — yenidən cəhd
            System.arraycopy(chunk, 0, out, off, got)
            off += got
        }
        return if (off == n) out else null
    }

    private fun readLoop() {
        while (running) {
            val head = readFully(Dongle.HEADER_SIZE) ?: continue
            val hdr = Dongle.parseHeader(head) ?: run { Log.w(TAG, "Səhv başlıq"); null } ?: continue
            val payload = if (hdr.length > 0) readFully(hdr.length) ?: continue else ByteArray(0)

            when (hdr.type) {
                Dongle.VIDEO -> onVideo(payload)
                Dongle.AUDIO -> onAudio(payload)
                Dongle.PLUGGED -> Log.i(TAG, "iPhone qoşuldu")
                Dongle.UNPLUGGED -> Log.i(TAG, "iPhone ayrıldı")
                else -> Log.d(TAG, "Mesaj tipi 0x%02x, %d bayt".format(hdr.type, hdr.length))
            }
        }
    }

    // ---------- Video ----------
    private fun startDecoder(w: Int, h: Int) {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(fmt, surfaceView.holder.surface, null, 0)
            start()
        }
    }

    /** Video payload: 20 baytlıq əlavə başlıq + xam H.264 (Annex-B). */
    private fun onVideo(payload: ByteArray) {
        if (payload.size <= 20) return
        val codec = decoder ?: return
        val inIdx = codec.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val buf = codec.getInputBuffer(inIdx)!!
            buf.clear()
            buf.put(payload, 20, payload.size - 20)
            codec.queueInputBuffer(inIdx, 0, payload.size - 20, System.nanoTime() / 1000, 0)
        }
        val info = MediaCodec.BufferInfo()
        var outIdx = codec.dequeueOutputBuffer(info, 0)
        while (outIdx >= 0) {
            codec.releaseOutputBuffer(outIdx, true)   // true = ekrana çək
            outIdx = codec.dequeueOutputBuffer(info, 0)
        }
    }

    // ---------- Səs (növbəti addım) ----------
    private fun onAudio(payload: ByteArray) {
        if (payload.size < 12) return
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val decodeType = b.int   // səs formatı (tezlik/kanal) — cədvəl üçün README-yə baxın
        // TODO: payload[12..] PCM məlumatını AudioTrack-ə yazmaq
    }

    // ---------- Toxunuş ----------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> Dongle.TOUCH_DOWN
            MotionEvent.ACTION_MOVE -> Dongle.TOUCH_MOVE
            MotionEvent.ACTION_UP -> Dongle.TOUCH_UP
            else -> return false
        }
        if (running) thread {
            send(Dongle.touch(action, e.x, e.y, surfaceView.width, surfaceView.height))
        }
        return true
    }

    private fun stop() {
        running = false
        decoder?.run { stop(); release() }
        decoder = null
        conn?.close()
        conn = null
    }

    override fun onDestroy() { stop(); super.onDestroy() }
}
