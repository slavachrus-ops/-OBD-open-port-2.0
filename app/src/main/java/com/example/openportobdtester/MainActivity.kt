package com.example.openportobdtester

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.openportobdtester.USB_PERMISSION"
        private const val TACTRIX_VID = 0x0403 // 1027
        private const val OPENPORT_PID = 0xCC4C // 52300 (Рабочий режим)
        private const val BOOTLOADER_PID = 0xCC4B // 52299 (Режим загрузчика)
    }

    private lateinit var usbManager: UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnConnect: Button
    private lateinit var btnCheckObdPower: Button
    private lateinit var btnTestCan: Button
    private lateinit var btnTestKline: Button

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openDevice(it) }
                    } else {
                        log("Ошибка: Пользователь отклонил доступ к USB.")
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                disconnectDevice()
                log("Адаптер отключен от USB.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollView = findViewById(R.id.scrollView)
        btnConnect = findViewById(R.id.btnConnect)
        btnCheckObdPower = findViewById(R.id.btnCheckObdPower)
        btnTestCan = findViewById(R.id.btnTestCan)
        btnTestKline = findViewById(R.id.btnTestKline)

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        btnConnect.setOnClickListener { scanForOpenPort() }
        btnCheckObdPower.setOnClickListener { checkObdPower() }
        btnTestCan.setOnClickListener { testCanBus() }
        btnTestKline.setOnClickListener { testKLine() }
    }

    private fun scanForOpenPort() {
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            log("USB-устройства не найдены. Проверьте переходник OTG и кабель Mini-USB.")
            return
        }

        for (device in deviceList.values) {
            log("Найдено USB: VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)}")
            if (device.vendorId == TACTRIX_VID && device.productId == BOOTLOADER_PID) {
                tvStatus.text = "Статус: OpenPort 2.0 в режиме BOOTLOADER!"
                log("ВНИМАНИЕ: Адаптер обнаружен с PID 0xCC4B (Bootloader). Требуется инициализация прошивки через ПК.")
                return
            }
            if (device.vendorId == TACTRIX_VID && device.productId == OPENPORT_PID) {
                if (usbManager.hasPermission(device)) {
                    openDevice(device)
                } else {
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                    usbManager.requestPermission(device, permissionIntent)
                }
                return
            }
        }
        log("Адаптер Tactrix OpenPort 2.0 не найден среди подключенных USB-устройств.")
    }

    private fun openDevice(device: UsbDevice) {
        thread {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                log("Не удалось открыть соединение с USB-устройством.")
                return@thread
            }

            var foundInterface: UsbInterface? = null
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep
                        if (ep.direction == UsbConstants.USB_DIR_OUT) bulkOut = ep
                    }
                }
                if (bulkIn != null && bulkOut != null) {
                    foundInterface = intf
                    break
                }
            }

            if (foundInterface == null || bulkIn == null || bulkOut == null) {
                log("Ошибка: Не найдены Bulk-эндпоинты для обмена данными.")
                connection.close()
                return@thread
            }

            connection.claimInterface(foundInterface, true)
            connection.controlTransfer(0x21, 0x22, 0x03, 0, null, 0, 1000)

            usbConnection = connection
            usbInterface = foundInterface
            epIn = bulkIn
            epOut = bulkOut

            log("USB-канал открыт. Инициализация процессора OpenPort 2.0...")

            sendCommand("\r\n")
            val initResp = sendCommand("ati\r\n")
            val statusResp = sendCommand("ata\r\n")

            runOnUiThread {
                tvStatus.text = "Статус: OpenPort 2.0 подключен и готов"
                btnCheckObdPower.isEnabled = true
                btnTestCan.isEnabled = true
                btnTestKline.isEnabled = true
            }
            log("Ответ ati (версия/серийник): $initResp")
            log("Ответ ata (состояние): $statusResp")
        }
    }

    private fun checkObdPower() {
        thread {
            log("--- Проверка питания разъема OBD-II (Пин 16 и Пины 4/5) ---")
            val resp = sendCommand("ata\r\n")
            val vbatResp = sendCommand("atget vbat\r\n")
            log("Данные телеметрии адаптера: $resp $vbatResp")
            log("Интерпретация: Если адаптер подключен к авто и возвращает напряжение ~11.8–14.5 В, то Пин 16 (+12В) и Пины 4/5 (Масса) в разъеме OBD-II исправны.")
        }
    }

    private fun testCanBus() {
        thread {
            log("--- Тест шины CAN (Пин 6 CAN-H / Пин 14 CAN-L) ---")
            val openCan = sendCommand("ato0005 0x0 500000 0\r\n")
            log("Открытие канала CAN 500 kbps: $openCan")

            val closeCan = sendCommand("atc0005\r\n")
            log("Закрытие канала CAN: $closeCan")
            log("Если канал открылся без ошибки ERR_HARDWARE / Bus Off — трансивер CAN и линии 6/14 не замкнуты.")
        }
    }

    private fun testKLine() {
        thread {
            log("--- Тест линии K-Line (Пин 7 — ISO 9141) ---")
            val openKline = sendCommand("ato0033 0x1 10400 0\r\n")
            log("Открытие канала K-Line: $openKline")

            val closeKline = sendCommand("atc0033\r\n")
            log("Закрытие канала K-Line: $closeKline")
        }
    }

    private fun sendCommand(cmd: String): String {
        val conn = usbConnection ?: return "НЕТ СОЕДИНЕНИЯ"
        val outEp = epOut ?: return "НЕТ EP_OUT"
        val inEp = epIn ?: return "НЕТ EP_IN"

        val outBytes = cmd.toByteArray(StandardCharsets.US_ASCII)
        val sent = conn.bulkTransfer(outEp, outBytes, outBytes.size, 1000)
        if (sent < 0) return "ОШИБКА ОТПРАВКИ"

        val buffer = ByteArray(1024)
        val received = conn.bulkTransfer(inEp, buffer, buffer.size, 1200)
        return if (received > 0) {
            String(buffer, 0, received, StandardCharsets.US_ASCII).trim()
        } else {
            "ТАЙМАУТ / НЕТ ОТВЕТА"
        }
    }

    private fun disconnectDevice() {
        usbInterface?.let { usbConnection?.releaseInterface(it) }
        usbConnection?.close()
        usbConnection = null
        runOnUiThread {
            tvStatus.text = "Статус: Адаптер отключен"
            btnCheckObdPower.isEnabled = false
            btnTestCan.isEnabled = false
            btnTestKline.isEnabled = false
        }
    }

    private fun log(message: String) {
        runOnUiThread {
            tvLog.append("$message\n")
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        disconnectDevice()
    }
}
