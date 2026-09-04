package com.example.xprinterapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Quản lý kết nối Bluetooth Classic (SPP) tới máy in nhiệt.
 * Xprinter XP-428A khi ghép nối Bluetooth hoạt động như một cổng serial
 * ảo (Serial Port Profile), dùng UUID chuẩn 00001101-0000-1000-8000-00805F9B34FB.
 *
 * LƯU Ý: Bạn cần ghép nối (pair) máy in với điện thoại trong phần
 * Cài đặt > Bluetooth của Android TRƯỚC khi dùng class này để kết nối,
 * vì Android Classic Bluetooth (SPP) yêu cầu thiết bị đã pair.
 */
class BluetoothPrinterHelper(private val context: Context) {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (e: Exception) {
            // Một số thiết bị/ROM tùy biến có thể ném lỗi bất thường ở đây;
            // coi như không có Bluetooth thay vì làm sập cả ứng dụng.
            null
        }
    }

    private var socket: BluetoothSocket? = null

    val isBluetoothSupported: Boolean get() = bluetoothAdapter != null

    val isBluetoothEnabled: Boolean
        get() = try {
            bluetoothAdapter?.isEnabled == true
        } catch (e: Exception) {
            false
        }

    val isConnected: Boolean get() = socket?.isConnected == true

    /**
     * Lấy danh sách thiết bị Bluetooth ĐÃ GHÉP NỐI (paired) sẵn trong hệ thống.
     * Cần quyền BLUETOOTH_CONNECT (Android 12+).
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            // Thiếu quyền (hiếm gặp dưới Android 12) -> trả về danh sách rỗng
            // thay vì làm sập ứng dụng.
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Kết nối tới thiết bị Bluetooth đã chọn.
     * PHẢI được gọi trên một luồng nền (background thread), KHÔNG gọi trên
     * main thread vì đây là thao tác I/O chặn (blocking).
     */
    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun connect(device: BluetoothDevice) {
        // Ngắt kết nối cũ (nếu có) trước khi tạo kết nối mới, tránh rò rỉ socket
        disconnect()

        // Dừng quá trình discovery nếu đang chạy, giúp kết nối ổn định hơn
        bluetoothAdapter?.cancelDiscovery()

        val tmpSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            tmpSocket.connect()
            socket = tmpSocket
        } catch (e: IOException) {
            // Một số máy in yêu cầu fallback dùng reflection tới channel 1
            try {
                val fallbackSocket = fallbackConnect(device)
                fallbackSocket.connect()
                socket = fallbackSocket
            } catch (fallbackError: Exception) {
                tmpSocket.closeQuietly()
                throw IOException("Không thể kết nối Bluetooth tới ${device.name}: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun fallbackConnect(device: BluetoothDevice): BluetoothSocket {
        val method = device.javaClass.getMethod(
            "createRfcommSocket", Int::class.javaPrimitiveType
        )
        return method.invoke(device, 1) as BluetoothSocket
    }

    /** Lấy OutputStream để ghi dữ liệu in. Trả về null nếu chưa kết nối. */
    fun getOutputStream(): OutputStream? {
        return try {
            socket?.outputStream
        } catch (e: IOException) {
            null
        }
    }

    /** Gửi dữ liệu (mảng byte lệnh ESC/POS) tới máy in qua Bluetooth */
    @Throws(IOException::class)
    fun print(data: ByteArray) {
        val stream = getOutputStream() ?: throw IOException("Chưa kết nối Bluetooth")
        EscPosCommands.writeTo(stream, data)
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (e: IOException) {
            // ignore
        } finally {
            socket = null
        }
    }

    private fun BluetoothSocket.closeQuietly() {
        try {
            close()
        } catch (e: IOException) {
            // ignore
        }
    }
}
