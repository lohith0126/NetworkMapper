//Type:kt

package com.example.networkmapper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var telephonyManager: TelephonyManager

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private var lastDbm: Int? = null
    private var lastMarker: Marker? = null

    private val handler = Handler()

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )

    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment

        mapFragment.getMapAsync(this)

        val missing = REQUIRED_PERMISSIONS.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            setupTelephony()
            setupLocation()
        }
    }

    // ===================== SIGNAL =====================

    private fun setupTelephony() {
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object :
                TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    handleSignal(signalStrength)
                }
            }
            telephonyManager.registerTelephonyCallback(mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    super.onSignalStrengthsChanged(signalStrength)
                    handleSignal(signalStrength)
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        }

        // 🔥 FORCE SIGNAL POLLING EVERY 3 SEC
        handler.post(object : Runnable {
            override fun run() {
                try {
                    val signal = telephonyManager.signalStrength
                    if (signal != null) {
                        handleSignal(signal)
                    }
                } catch (e: Exception) {
                    Log.e("SIGNAL", "Polling error: ${e.message}")
                }
                handler.postDelayed(this, 3000)
            }
        })
    }

    private fun handleSignal(signalStrength: SignalStrength) {

        val dbm: Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            signalStrength.cellSignalStrengths.firstOrNull()?.dbm
        } else null

        val finalDbm: Int = dbm ?: run {
            @Suppress("DEPRECATION")
            val gsm = signalStrength.gsmSignalStrength
            if (gsm in 0..31) (-113 + 2 * gsm) else return
        }

        Log.d("SIGNAL", "dBm: $finalDbm")

        lastDbm = finalDbm
        updateLastMarker(finalDbm)
    }

    // ===================== LOCATION =====================

    private fun setupLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationRequest = LocationRequest.create().apply {
            interval = 3000
            fastestInterval = 2000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val dbm = lastDbm ?: return

                val position = LatLng(location.latitude, location.longitude)

                val color = signalColor(dbm)

                if (lastMarker == null) {
                    lastMarker = mMap.addMarker(
                        MarkerOptions()
                            .position(position)
                            .title("$dbm dBm")
                            .snippet(signalLabel(dbm))
                            .icon(BitmapDescriptorFactory.defaultMarker(color))
                    )
                } else {
                    lastMarker?.position = position
                    lastMarker?.title = "$dbm dBm"
                    lastMarker?.snippet = signalLabel(dbm)
                    lastMarker?.setIcon(BitmapDescriptorFactory.defaultMarker(color))
                }

                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 17f))
            }
        }
    }

    private fun updateLastMarker(dbm: Int) {
        runOnUiThread {
            lastMarker?.let {
                it.title = "$dbm dBm"
                it.snippet = signalLabel(dbm)
                it.setIcon(BitmapDescriptorFactory.defaultMarker(signalColor(dbm)))
                it.showInfoWindow()
            }
        }
    }

    // ===================== UTILS =====================

    private fun signalColor(dbm: Int): Float = when {
        dbm > -85 -> BitmapDescriptorFactory.HUE_GREEN
        dbm > -100 -> BitmapDescriptorFactory.HUE_YELLOW
        else -> BitmapDescriptorFactory.HUE_RED
    }

    private fun signalLabel(dbm: Int): String = when {
        dbm > -85 -> "🟢 Strong"
        dbm > -100 -> "🟡 Medium"
        else -> "🔴 Weak"
    }

    // ===================== MAP =====================

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            enableLocation()
        }
    }

    private fun enableLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            mMap.isMyLocationEnabled = true

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper
            )
        }
    }

    // ===================== PERMISSION =====================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                setupTelephony()
                setupLocation()
                if (::mMap.isInitialized) enableLocation()
            }
        }
    }
}