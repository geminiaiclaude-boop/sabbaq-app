import os

files = {
    'app/src/main/AndroidManifest.xml': '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".App"
        android:allowBackup="false"
        android:label="Qannas"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.NoTitleBar">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.SniperA11yService"
            android:exported="true"
            android:label="Qannas Auto-Catcher"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <service
            android:name=".service.KeeperService"
            android:exported="false"
            android:foregroundServiceType="specialUse" />

        <receiver
            android:name=".receiver.BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>''',

    'app/src/main/res/xml/accessibility_service_config.xml': '''<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews|flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:notificationTimeout="50" />''',

    'app/src/main/java/app/qannas/App.kt': '''package app.qannas

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
    }
}''',

    'app/src/main/java/app/qannas/Settings.kt': '''package app.qannas

import android.content.Context
import android.content.SharedPreferences
import app.qannas.core.Filters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object Settings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("qannas_prefs", Context.MODE_PRIVATE)
    }

    var filters: Filters
        get() {
            val str = prefs.getString("filters", null) ?: return Filters()
            return try { Json.decodeFromString(str) } catch (e: Exception) { Filters() }
        }
        set(value) {
            prefs.edit().putString("filters", Json.encodeToString(value)).apply()
        }

    var isActive: Boolean
        get() = prefs.getBoolean("is_active", false)
        set(value) = prefs.edit().putBoolean("is_active", value).apply()
}''',

    'app/src/main/java/app/qannas/service/SniperA11yService.kt': '''package app.qannas.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.qannas.Settings
import app.qannas.core.*
import java.util.Calendar

class SniperA11yService : AccessibilityService() {

    private lateinit var parser: OfferParser
    private val clickGate = ClickGate(perOfferSuppressMs = 20000, minIntervalMs = 500)

    override fun onServiceConnected() {
        super.onServiceConnected()
        parser = OfferParser(Profiles.bundled())
        Log.d("SniperA11y", "Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!Settings.isActive) return

        val rootNode = rootInActiveWindow ?: return
        
        try {
            val screenNode = rootNode.toScreenNode()
            val parsed = parser.parse(screenNode)

            parsed.offer?.let { offer ->
                val cal = Calendar.getInstance()
                val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                
                val verdict = OfferEvaluator.evaluate(offer, Settings.filters, minuteOfDay)
                Log.d("SniperA11y", "Offer: \ | Verdict: \")
                
                if (verdict.accept && clickGate.tryAcquire(offer.fingerprint, System.currentTimeMillis())) {
                    parsed.acceptNode?.let { acceptNode ->
                        performClick(acceptNode.bounds)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SniperA11y", "Parse error", e)
        } finally {
            rootNode.recycle()
        }
    }

    private fun performClick(bounds: Bounds) {
        val path = Path().apply {
            moveTo(bounds.centerX.toFloat(), bounds.centerY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 10, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
        Log.d("SniperA11y", "Clicked at \, \")
    }

    override fun onInterrupt() {}

    private fun AccessibilityNodeInfo.toScreenNode(): ScreenNode {
        val rect = Rect()
        this.getBoundsInScreen(rect)
        val b = Bounds(rect.left, rect.top, rect.right, rect.bottom)
        
        val children = mutableListOf<ScreenNode>()
        for (i in 0 until this.childCount) {
            val child = this.getChild(i)
            if (child != null) {
                children.add(child.toScreenNode())
                child.recycle()
            }
        }
        
        return ScreenNode(
            id = this.hashCode(),
            text = this.text?.toString(),
            description = this.contentDescription?.toString(),
            viewId = this.viewIdResourceName,
            className = this.className?.toString(),
            clickable = this.isClickable,
            bounds = b,
            children = children
        )
    }
}''',

    'app/src/main/java/app/qannas/service/KeeperService.kt': '''package app.qannas.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class KeeperService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = NotificationCompat.Builder(this, "keeper_channel")
            .setContentTitle("Qannas is Running")
            .setContentText("Listening for offers...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        
        startForeground(1, notification)
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "keeper_channel",
                "Keeper Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}''',

    'app/src/main/java/app/qannas/receiver/BootReceiver.kt': '''package app.qannas.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.qannas.service.KeeperService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val serviceIntent = Intent(context, KeeperService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}''',

    'app/src/main/java/app/qannas/ui/MainActivity.kt': '''package app.qannas.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.qannas.Settings
import app.qannas.service.KeeperService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        onOpenSettings = {
                            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onToggleKeeper = { start ->
                            val intent = Intent(this, KeeperService::class.java)
                            if (start) {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    startForegroundService(intent)
                                } else {
                                    startService(intent)
                                }
                            } else {
                                stopService(intent)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(onOpenSettings: () -> Unit, onToggleKeeper: (Boolean) -> Unit) {
    var isActive by remember { mutableStateOf(Settings.isActive) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Qannas Auto-Catcher", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Service Active:")
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = isActive,
                onCheckedChange = { 
                    isActive = it
                    Settings.isActive = it
                    onToggleKeeper(it)
                }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onOpenSettings) {
            Text("Open Accessibility Settings")
        }
    }
}'''
}

for path, content in files.items():
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f"Created {path}")

