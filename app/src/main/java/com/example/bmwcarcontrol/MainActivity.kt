package com.example.bmwcarcontrol

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var deviceSpinner: Spinner
    private var pairedDevices: List<BluetoothDevice> = emptyList()

    private val sendHandler = Handler(Looper.getMainLooper())
    private var currentPitchFlag = 0
    private var currentYawFlag = 0
    private var lightsOn = false
    private val sendIntervalMs = 100L
    private val drivePower = 200

    private val sendRunnable = object : Runnable {
        override fun run() {
            sendPacket()
            sendHandler.postDelayed(this, sendIntervalMs)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                loadPairedDevices()
            } else {
                Toast.makeText(
                    this,
                    "Bluetooth permission is required to list paired devices",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        deviceSpinner = findViewById(R.id.deviceSpinner)

        findViewById<Button>(R.id.refreshButton).setOnClickListener { ensurePermissionsThenLoad() }
        findViewById<Button>(R.id.connectButton).setOnClickListener { connectToSelected() }

        setupHoldButton(R.id.btnForward, pitchFlag = 1, yawFlag = 0)
        setupHoldButton(R.id.btnBackward, pitchFlag = 2, yawFlag = 0)
        setupHoldButton(R.id.btnLeft, pitchFlag = 0, yawFlag = 1)
        setupHoldButton(R.id.btnRight, pitchFlag = 0, yawFlag = 2)

        findViewById<Button>(R.id.btnStop).setOnClickListener { stopDriving() }

        findViewById<Button>(R.id.btnLights).setOnClickListener {
            lightsOn = !lightsOn
            sendPacket()
            Toast.makeText(this, if (lightsOn) "AUX byte: ON" else "AUX byte: OFF", Toast.LENGTH_SHORT).show()
        }

        ensurePermissionsThenLoad()
    }

    private fun ensurePermissionsThenLoad() {
        if (bluetoothAdapter == null) {
            statusText.text = "This device has no Bluetooth adapter"
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
            val notGranted = needed.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (notGranted.isNotEmpty()) {
                requestPermissionLauncher.launch(notGranted.toTypedArray())
                return
            }
        }
        loadPairedDevices()
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            statusText.text = "Please turn on Bluetooth and pair the car first"
            return
        }
        pairedDevices = adapter.bondedDevices.toList()
        val names = pairedDevices.map { "${it.name} (${it.address})" }
        if (names.isEmpty()) {
            statusText.text = "No paired devices found. Pair the car in Android Bluetooth settings first (PIN usually 0000)."
        } else {
            statusText.text = "Select the car below, then tap Connect"
        }
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    }

    @SuppressLint("MissingPermission")
    private fun connectToSelected() {
        val position = deviceSpinner.selectedItemPosition
        if (position < 0 || position >= pairedDevices.size) {
            Toast.makeText(this, "Pick a paired device first", Toast.LENGTH_SHORT).show()
            return
        }
        val device = pairedDevices[position]
        statusText.text = "Connecting to ${device.name}..."
        ioExecutor.execute {
            try {
                bluetoothAdapter?.cancelDiscovery()
                val sock = device.createRfcommSocketToServiceRecord(sppUuid)
                sock.connect()
                socket = sock
                outputStream = sock.outputStream
                runOnUiThread { statusText.text = "Connected to ${device.name}" }
            } catch (e: Exception) {
                socket = null
                outputStream = null
                runOnUiThread {
                    statusText.text = "Connection failed: ${e.message}"
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHoldButton(viewId: Int, pitchFlag: Int, yawFlag: Int) {
        findViewById<Button>(viewId).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> startDriving(pitchFlag, yawFlag)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopDriving()
            }
            false
        }
    }

    private fun startDriving(pitchFlag: Int, yawFlag: Int) {
        currentPitchFlag = pitchFlag
        currentYawFlag = yawFlag
        sendHandler.removeCallbacks(sendRunnable)
        sendHandler.post(sendRunnable)
    }

    private fun stopDriving() {
        currentPitchFlag = 0
        currentYawFlag = 0
        sendHandler.removeCallbacks(sendRunnable)
        sendPacket()
    }

    private fun sendPacket() {
        val stream = outputStream ?: return
        val trimer = if (lightsOn) 15 else 0
        val packet = buildPacket(
            pitchFlag = currentPitchFlag,
            pitch = if (currentPitchFlag != 0) drivePower else 0,
            yawFlag = currentYawFlag,
            yaw = if (currentYawFlag != 0) drivePower else 0,
            trimer = trimer
        )
        ioExecutor.execute {
            try {
                stream.write(packet)
                stream.flush()
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Lost connection: ${e.message}" }
                outputStream = null
            }
        }
    }

    private fun buildPacket(pitchFlag: Int, pitch: Int, yawFlag: Int, yaw: Int, trimer: Int): ByteArray {
        val pClamped = pitch.coerceIn(0, 255)
        val yClamped = yaw.coerceIn(0, 255)
        val trimClamped = trimer.coerceIn(0, 15)

        val flagByte = (yawFlag and 0x3) or ((pitchFlag and 0x3) shl 2)
        val trimerByte = (trimClamped shl 4) and 0xFF
        val checkNum = (pClamped + yClamped + 1 + trimClamped) and 0xFF

        val wireOrder = intArrayOf(flagByte, yClamped, pClamped, trimerByte, checkNum)

        val sb = StringBuilder("0p")
        for (b in wireOrder) {
            sb.append(String.format("%02x", b and 0xFF))
        }
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    override fun onDestroy() {
        super.onDestroy()
        sendHandler.removeCallbacks(sendRunnable)
        ioExecutor.execute {
            try {
                outputStream?.flush()
                outputStream?.close()
                socket?.close()
            } catch (_: Exception) {
            }
        }
        ioExecutor.shutdown()
    }
}
