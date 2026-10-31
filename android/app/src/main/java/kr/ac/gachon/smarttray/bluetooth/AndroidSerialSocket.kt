package kr.ac.gachon.smarttray.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import java.io.IOException

@SuppressLint("MissingPermission") // Service gates permission and handles revocation.
class AndroidSerialSocket(device: BluetoothDevice) : SerialSocket {
    private val socket = device.createRfcommSocketToServiceRecord(SppProfile.SERVICE_UUID)
    override fun connect() { socket.connect(); if (!socket.isConnected) throw IOException("RFCOMM socket did not connect") }
    override val input get() = socket.inputStream
    override val output get() = socket.outputStream
    override fun close() = socket.close()
}
