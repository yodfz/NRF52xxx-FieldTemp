package com.example.pandatemperature.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.pandatemperature.MainActivity
import com.example.pandatemperature.R

/** 天气预警通知渠道 ID */
const val WEATHER_ALERT_CHANNEL_ID = "weather_alert"

/** 暴风雨预警通知 ID */
const val NOTIFICATION_ID_STORM = 3001

/**
 * 发送暴风雨预警通知（高优先级、声音、震动）
 * 点击进入 MainActivity
 */
fun showStormWarningNotification(context: Context, message: String) {
    val appName = context.getString(R.string.app_name)
    createWeatherAlertChannel(context)

    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        NOTIFICATION_ID_STORM,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, WEATHER_ALERT_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("暴风雨预警")
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .setDefaults(NotificationCompat.DEFAULT_ALL)
        .setVibrate(longArrayOf(0, 500, 200, 500))
        .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (NotificationManagerCompat.from(context).canUseFullScreenIntent()) {
            // 已授权通知时直接显示
        }
    }
    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_STORM, notification)
}

/**
 * 创建天气预警通知渠道（高重要性、声音、震动）
 */
private fun createWeatherAlertChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
        WEATHER_ALERT_CHANNEL_ID,
        context.getString(R.string.weather_alert_channel_name),
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = context.getString(R.string.weather_alert_channel_description)
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 500, 200, 500)
        setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM), null)
    }
    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .createNotificationChannel(channel)
}
