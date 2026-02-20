package com.example.lab5

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.*
import android.location.Location
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import kotlin.math.*

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var vibrator: Vibrator? = null

    private val azimuthState = mutableStateOf(0f)
    private val distanceState = mutableStateOf(0f)
    private val bearingState = mutableStateOf(0f)
    private val accuracyState = mutableStateOf("Checking...")

    // Дані для рівня (бульбашки)
    private val rollState = mutableStateOf(0f)
    private val pitchState = mutableStateOf(0f)

    private val targetLat = mutableStateOf(50.4501f)
    private val targetLon = mutableStateOf(30.5234f)

    private var smoothedGravity = FloatArray(3)
    private var smoothedGeomagnetic = FloatArray(3)
    private var lastVibrationTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        startLocationUpdates()
        enableEdgeToEdge()
        setContent {
            var isSettingsMode by remember { mutableStateOf(true) }

            Box(modifier = Modifier.fillMaxSize()) {
                if (isSettingsMode) {
                    TargetSettingsScreen(targetLat.value, targetLon.value) { lat, lon ->
                        targetLat.value = lat
                        targetLon.value = lon
                        isSettingsMode = false
                    }
                } else {
                    CompassScreen(
                        azimuthState.value,
                        distanceState.value,
                        bearingState.value,
                        accuracyState.value,
                        pitchState.value,
                        rollState.value
                    )
                }

                FloatingActionButton(
                    onClick = { isSettingsMode = !isSettingsMode },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).padding(bottom = 32.dp),
                    containerColor = Color(0xFF6200EE)
                ) {
                    Icon(imageVector = if (isSettingsMode) Icons.Default.LocationOn else Icons.Default.List, contentDescription = null, tint = Color.White)
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).build()
        fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val lastLocation = locationResult.lastLocation ?: return
                val results = FloatArray(1)
                Location.distanceBetween(lastLocation.latitude, lastLocation.longitude, targetLat.value.toDouble(), targetLon.value.toDouble(), results)
                distanceState.value = results[0]
                val targetLoc = Location("target").apply { latitude = targetLat.value.toDouble(); longitude = targetLon.value.toDouble() }
                bearingState.value = lastLocation.bearingTo(targetLoc)
            }
        }, Looper.getMainLooper())
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            smoothedGravity = lowPass(event.values.clone(), smoothedGravity)
            // Розрахунок нахилу для бульбашки
            pitchState.value = smoothedGravity[1] // Нахил вперед/назад
            rollState.value = smoothedGravity[0]  // Нахил вліво/вправо
        }
        if (event?.sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            smoothedGeomagnetic = lowPass(event.values.clone(), smoothedGeomagnetic)
        }

        val R = FloatArray(9)
        if (SensorManager.getRotationMatrix(R, null, smoothedGravity, smoothedGeomagnetic)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(R, orientation)
            var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (azimuth < 0) azimuth += 360
            azimuthState.value = azimuth
            checkVibration(azimuth)
        }
    }

    private fun lowPass(input: FloatArray, output: FloatArray): FloatArray {
        val alpha = 0.12f
        for (i in input.indices) output[i] = output[i] + alpha * (input[i] - output[i])
        return output
    }

    private fun checkVibration(currentAzimuth: Float) {
        val target = if (bearingState.value < 0) bearingState.value + 360f else bearingState.value
        var diff = abs(currentAzimuth - target)
        if (diff > 180) diff = 360 - diff
        if (diff < 5f && System.currentTimeMillis() - lastVibrationTime > 1500) {
            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            lastVibrationTime = System.currentTimeMillis()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            accuracyState.value = when (accuracy) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High"
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium"
                else -> "Low (Calibrate!)"
            }
        }
    }
}

@Composable
fun TargetSettingsScreen(lat: Float, lon: Float, onSave: (Float, Float) -> Unit) {
    var latText by remember { mutableStateOf(lat.toString()) }
    var lonText by remember { mutableStateOf(lon.toString()) }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Налаштування цілі", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(value = latText, onValueChange = { latText = it }, label = { Text("Latitude") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(value = lonText, onValueChange = { lonText = it }, label = { Text("Longitude") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { onSave(latText.toFloatOrNull() ?: 0f, lonText.toFloatOrNull() ?: 0f) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(12.dp)) {
            Text("ВСТАНОВИТИ КУРС")
        }
    }
}

@Composable
fun CompassScreen(azimuth: Float, distance: Float, bearing: Float, accuracy: String, pitch: Float, roll: Float) {
    var previousAzimuth by remember { mutableStateOf(azimuth) }
    val adjustedAzimuth = remember(azimuth) {
        var delta = azimuth - previousAzimuth
        if (delta > 180) delta -= 360 else if (delta < -180) delta += 360
        previousAzimuth + delta
    }
    val animatedAzimuth by animateFloatAsState(targetValue = adjustedAzimuth, animationSpec = tween(150), label = "")
    previousAzimuth = animatedAzimuth

    val targetAzimuth = if (bearing < 0) bearing + 360f else bearing
    var diff = targetAzimuth - azimuth
    if (diff > 180) diff -= 360 else if (diff < -180) diff += 360

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF000428), Color(0xFF004E92))))) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {

            Text(
                text = "Accuracy: $accuracy",
                color = if (accuracy.contains("Low")) Color.Red else Color.Green.copy(0.7f),
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(if (abs(diff) < 8f) "ЦІЛЬ ПОПЕРЕДУ" else if (diff > 0) "ПОВЕРНІТЬ ПРАВОРУЧ" else "ПОВЕРНІТЬ ЛІВОРУЧ",
                color = if (abs(diff) < 8f) Color.Green else Color.White.copy(0.7f), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)

            Spacer(modifier = Modifier.height(30.dp))

            Card(modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White.copy(0.1f))) {
                Row(modifier = Modifier.padding(15.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("METERS", color = Color.White.copy(0.5f), fontSize = 10.sp)
                        Text("${distance.roundToInt()}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ANGLE", color = Color.White.copy(0.5f), fontSize = 10.sp)
                        Text("${azimuth.roundToInt()}°", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(300.dp)) {
                    val radius = size.minDimension / 2
                    val center = Offset(size.width / 2, size.height / 2)

                    rotate(-animatedAzimuth, center) {
                        for (i in 0 until 360 step 10) {
                            val isMajor = i % 30 == 0
                            val angleRad = Math.toRadians(i.toDouble())
                            drawLine(color = if (isMajor) Color.White else Color.White.copy(0.3f),
                                start = Offset(center.x + (radius - 25f) * sin(angleRad).toFloat(), center.y - (radius - 25f) * cos(angleRad).toFloat()),
                                end = Offset(center.x + radius * sin(angleRad).toFloat(), center.y - radius * cos(angleRad).toFloat()),
                                strokeWidth = if (isMajor) 4f else 1.5f)
                        }

                        drawContext.canvas.nativeCanvas.apply {
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                textSize = 45f
                                textAlign = android.graphics.Paint.Align.CENTER
                                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            }
                            val textOffset = 60f
                            paint.color = android.graphics.Color.RED
                            drawText("N", center.x, center.y - radius + textOffset, paint)
                            paint.color = android.graphics.Color.WHITE
                            drawText("S", center.x, center.y + radius - textOffset + 20f, paint)
                            drawText("E", center.x + radius - textOffset, center.y + 15f, paint)
                            drawText("W", center.x - radius + textOffset, center.y + 15f, paint)
                        }

                        rotate(bearing, center) {
                            drawPath(Path().apply {
                                moveTo(center.x, center.y - radius + 40f)
                                lineTo(center.x - 15f, center.y - radius + 80f)
                                lineTo(center.x + 15f, center.y - radius + 80f)
                                close()
                            }, color = Color.Green)
                        }
                    }

                    drawCircle(Color.White.copy(0.1f), radius = 50f, center = center)
                    drawCircle(Color.White.copy(0.2f), radius = 50f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))

                    val bubbleX = (roll * 5f).coerceIn(-45f, 45f)
                    val bubbleY = (pitch * 5f).coerceIn(-45f, 45f)
                    val isLevel = abs(roll) < 0.5f && abs(pitch) < 0.5f

                    drawCircle(
                        color = if (isLevel) Color.Green else Color.White,
                        radius = 10f,
                        center = Offset(center.x + bubbleX, center.y + bubbleY)
                    )

                    drawLine(Color.Red, center, Offset(center.x, center.y - radius), 5f)
                }
            }
        }
    }
}