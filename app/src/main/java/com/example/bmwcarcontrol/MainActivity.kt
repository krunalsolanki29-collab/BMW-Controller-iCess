package com.example.bmwcarcontrol

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.widget.SeekBar
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
    private var allLightsOn = false
    private val sendIntervalMs = 100L
    private val drivePower = 255

    private var manualModeActive = false
    private var manualPitchFlag = 0
    private var manualPitchMag = 0
    private var manualYawFlag = 0
    private var manualYawMag = 0
    private var manualTrim = 0
    private lateinit var packetPreview: TextView

    private val defaultTrimer = 50

    private var isStreaming = false

    private val sendRunnable = object : Runnable {
        override fun run() {
            if (!isStreaming) return
            sendPacket()
            sendHandler.postDelayed(this, sendIntervalMs)
        }
    }

    private fun startStreaming() {
        if (isStreaming) return
        isStreaming = true
        sendHandler.post(sendRunnable)
    }

    private fun stopStreaming() {
        isStreaming = false
        sendHandler.removeCallbacks(sendRunnable)
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

        findViewById<Button>(R.id.startButton).setOnClickListener {
            currentPitchFlag = 0
            currentYawFlag = 0
            allLightsOn = false
            startStreaming()
            statusText.text = "Streaming started"
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopStreaming()
            statusText.text = "Streaming stopped"
        }

        setupHoldButton(R.id.btnForward, pitchFlag = 1, yawFlag = 0)
        setupHoldButton(R.id.btnBackward, pitchFlag = 2, yawFlag = 0)
        setupHoldButton(R.id.btnLeft, pitchFlag = 0, yawFlag = 1)
        setupHoldButton(R.id.btnRight, pitchFlag = 0, yawFlag = 2)

        findViewById<Button>(R.id.btnStop).setOnClickListener { stopDriving() }

        findViewById<Button>(R.id.btnLights).setOnClickListener {
            allLightsOn = !allLightsOn
            sendPacket()
            Toast.makeText(this, if (allLightsOn) "All 4 lights: ON" else "Lights: default", Toast.LENGTH_SHORT).show()
        }

        packetPreview = findViewById(R.id.packetPreview)
        setupManualSlider(R.id.pitchFlagSeek, R.id.pitchFlagLabel, "Pitch flag") { manualPitchFlag = it }
        setupManualSlider(R.id.pitchMagSeek, R.id.pitchMagLabel, "Pitch magnitude") { manualPitchMag = it }
        setupManualSlider(R.id.yawFlagSeek, R.id.yawFlagLabel, "Yaw flag") { manualYawFlag = it }
        setupManualSlider(R.id.yawMagSeek, R.id.yawMagLabel, "Yaw magnitude") { manualYawMag = it }
        setupManualSlider(R.id.trimSeek, R.id.trimLabel, "Trim/light nibble") { manualTrim = it }

        ensurePermissionsThenLoad()
    }

    private fun setupManualSlider(seekId: Int, labelId: Int, labelText: String, onChange: (Int) -> Unit) {
        val seek = findViewById<SeekBar>(seekId)
        val label = findViewById<TextView>(labelId)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "$labelText: $progress"
                if (fromUser) {
                    manualModeActive = true
                    onChange(progress)
                    sendPacket()
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
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
                var sock: BluetoothSocket
                try {
                    sock = device.createRfcommSocketToServiceRecord(sppUuid)
                    sock.connect()
                } catch (sdpFailure: Exception) {
                    val fallbackSock = device.javaClass
                        .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                    fallbackSock.connect()
                    sock = fallbackSock
                }
                socket = sock
                outputStream = sock.outputStream
                runOnUiThread {
                    statusText.text = "Connected to ${device.name}"
                    startStreaming()
                }
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
        manualModeActive = false
        currentPitchFlag = pitchFlag
        currentYawFlag = yawFlag
    }

    private fun stopDriving() {
        manualModeActive = false
        currentPitchFlag = 0
        currentYawFlag = 0
    }

    private fun sendPacket() {
        val stream = outputStream ?: return

        val packet = if (manualModeActive) {
            buildPacket(
                pitchFlag = manualPitchFlag,
                pitch = manualPitchMag,
                yawFlag = manualYawFlag,
                yaw = manualYawMag,
                trimer = manualTrim
            )
        } else {
            val trimer = if (allLightsOn) 15 else defaultTrimer
            buildPacket(
                pitchFlag = currentPitchFlag,
                pitch = if (currentPitchFlag != 0) drivePower else 0,
                yawFlag = currentYawFlag,
                yaw = if (currentYawFlag != 0) drivePower else 0,
                trimer = trimer
            )
        }

        runOnUiThread {
            packetPreview.text = "Packet: " + packet.toString(Charsets.US_ASCII)
        }

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
        val trimClamped = trimer.coerceIn(0, 255)

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
        stopStreaming()
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
