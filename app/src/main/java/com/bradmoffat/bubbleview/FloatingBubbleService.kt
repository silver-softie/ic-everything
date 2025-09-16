package com.bradmoffat.bubbleview

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class FloatingBubbleService : Service() {

    // --- CONFIGURATION ---
    private val BIKE_MAC_ADDRESS = "FE:E8:C4:2B:4D:9A" // Use your bike's address
    private val FTMS_SERVICE_UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
    private val INDOOR_BIKE_DATA_CHAR_UUID = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- SERVICE OBJECTS ---
    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var cadenceText: TextView
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var gattClient: BluetoothGatt? = null
    private var serviceInitialized = false
    private var isConfirmationVisible = false

    // Handler to post UI updates to the main thread
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        // Do not add heavy initialization here. It will be moved to onStartCommand
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, "floating_bubble_channel")
            .setContentTitle("Cadence Monitor Running")
            .setContentText("Monitoring your bike data...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)

        // Perform initialization only once
        if (!serviceInitialized) {
            initializeService()
            serviceInitialized = true
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "floating_bubble_channel",
                "Floating Bubble Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun initializeService() {
        // Initialize Bluetooth objects
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Initialize UI components
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        bubbleView = inflater.inflate(R.layout.floating_bubble_layout, null)

        statusText = bubbleView.findViewById(R.id.statusText)
        speedText = bubbleView.findViewById(R.id.speedText)
        cadenceText = bubbleView.findViewById(R.id.cadenceText)

        // Add the bubble view to the window
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        // Add the following line to move the window to the top-left
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(bubbleView, params)

        // Add a click listener to the bubble to show a close confirmation
        bubbleView.setOnClickListener {
            if (!isConfirmationVisible) {
                showCloseConfirmation()
            }
        }

        // Connect to the bike
        connectToBike()
    }


    private fun showCloseConfirmation() {
        isConfirmationVisible = true
        val confirmationView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#80000000")) // Semi-transparent black background
        }

        val confirmationText = TextView(this).apply {
            text = "Are you sure you want to close the app?"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val yesButton = Button(this).apply {
            text = "Yes"
            setOnClickListener {
                windowManager.removeView(confirmationView)
                isConfirmationVisible = false
                stopSelf() // Stop the service
            }
        }

        val noButton = Button(this).apply {
            text = "No"
            setOnClickListener {
                windowManager.removeView(confirmationView)
                isConfirmationVisible = false
            }
        }

        buttonLayout.addView(yesButton)
        buttonLayout.addView(noButton)
        confirmationView.addView(confirmationText)
        confirmationView.addView(buttonLayout)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        windowManager.addView(confirmationView, params)
    }

    // --- BLUETOOTH LOGIC ---

    @SuppressLint("MissingPermission")
    private fun connectToBike() {
        if (!bluetoothAdapter.isEnabled) {
            updateUI("Please turn on Bluetooth.")
            return
        }

        val device = bluetoothAdapter.getRemoteDevice(BIKE_MAC_ADDRESS)
        if (device == null) {
            updateUI("Bike not found. Please ensure it is powered on and in range.")
            Log.e("BLE", "Bluetooth device with MAC address $BIKE_MAC_ADDRESS not found.")
            return
        }

        Log.d("BLE", "Attempting to connect directly to bike at MAC: ${device.address}")
        safeConnectGatt(device)
    }

    @SuppressLint("MissingPermission")
    private fun safeConnectGatt(device: BluetoothDevice) {
        gattClient = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d("BLE", "Connected to GATT server.")
                    safeDiscoverServices(gatt)
                    updateUI("Connected. Discovering services...")
                } else {
                    Log.e("BLE", "Connection failed with status: $status")
                    updateUI("Connection failed. Retrying...")
                    safeCloseGatt(gatt)
                    connectToBike()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BLE", "Disconnected from GATT server.")
                safeCloseGatt(gattClient)
                updateUI("Disconnected. Reconnecting...")
                connectToBike()
            }
        }

        @SuppressLint("MissingPermission")
        private fun safeDiscoverServices(gatt: BluetoothGatt) {
            Log.d("BLE", "safeDiscoverServices")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val ftmsService = gatt.getService(FTMS_SERVICE_UUID)
                if (ftmsService != null) {
                    Log.d("BLE", "FTMS service discovered.")
                    val bikeDataChar = ftmsService.getCharacteristic(INDOOR_BIKE_DATA_CHAR_UUID)
                    if (bikeDataChar != null) {
                        Log.d("BLE", "Found bike data characteristic.")
                        @SuppressLint("MissingPermission")
                        val setNotification = gatt.setCharacteristicNotification(bikeDataChar, true)
                        if (!setNotification) {
                            Log.e("BLE", "Failed to set characteristic notification.")
                            updateUI("Setup failed. Check permissions.")
                            return
                        }

                        val descriptor = bikeDataChar.getDescriptor(CCCD_UUID)
                        safeWriteDescriptor(gatt, descriptor)
                        updateUI("Connected")
                    } else {
                        Log.e("BLE", "Indoor Bike Data Characteristic not found.")
                        updateUI("Service found, but characteristic not found.")
                    }
                } else {
                    Log.e("BLE", "FTMS Service not found.")
                    updateUI("FTMS Service not found.")
                }
            } else {
                Log.e("BLE", "Service discovery failed with status: $status")
                updateUI("Service discovery failed.")
            }
        }

        @SuppressLint("MissingPermission")
        private fun safeWriteDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "Successfully wrote descriptor.")
            } else {
                Log.e("BLE", "Failed to write descriptor with status: $status")
                updateUI("Subscription failed.")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            handleCharacteristicChanged(characteristic, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            super.onCharacteristicChanged(gatt, characteristic)
            val value = characteristic.value
            if (value != null) {
                handleCharacteristicChanged(characteristic, value)
            } else {
                Log.e("BLE", "Received null value from characteristic.value for characteristic ${characteristic.uuid}")
            }
        }

        private fun handleCharacteristicChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == INDOOR_BIKE_DATA_CHAR_UUID) {
                val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
                val flags = buffer.short.toInt()

                var rawCadence: Float? = null
                var rawPower: Float? = null

                if ((flags and 0x04) != 0) {
                    rawCadence = buffer.short.toFloat() / 100.0f
                }
                if ((flags and 0x40) != 0) {
                    rawPower = buffer.short.toFloat() / 2.0f
                }

                uiHandler.post {
                    if (rawCadence != null) {
                        speedText.text = "Speed: %.2f km/h".format(rawCadence)
                    } else {
                        speedText.text = "Speed: -- km/h"
                    }

                    if (rawPower != null) {
                        cadenceText.text = "Cadence: %.1f RPM".format(rawPower)
                    } else {
                        cadenceText.text = "Cadence: -- RPM"
                    }
                }
            }
        }
    }

    private fun updateUI(message: String) {
        uiHandler.post {
            statusText.text = message
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::bubbleView.isInitialized) {
            windowManager.removeView(bubbleView)
        }
        safeCloseGatt(gattClient)
    }

    @SuppressLint("MissingPermission")
    private fun safeCloseGatt(gatt: BluetoothGatt?) {
        gatt?.close()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
