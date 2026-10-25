package net.easecation.mcpywrap

import android.app.Activity
import android.app.ActivityOptions
import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.util.Rational
import dev.mcpywrap.workspace.WorkspaceRepository
import org.json.JSONObject
import java.io.File

/** Supplies the same-task source Activity required by Android's launch-into-PiP API. */
class GamePipLauncherActivity : Activity() {
    private val handler = Handler(android.os.Looper.getMainLooper())
    private var launched = false
    private var callerRestored = false
    private var observing = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        launched = state?.getBoolean("launched") ?: false
        callerRestored = state?.getBoolean("callerRestored") ?: false
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("launched", launched)
        state.putBoolean("callerRestored", callerRestored)
        super.onSaveInstanceState(state)
    }

    override fun onResume() {
        super.onResume()
        if (observing) return
        val session = intent.getStringExtra("session") ?: run { finish(); return }
        fun validAttempt():Boolean=runCatching{
            val pending=JSONObject(File(noBackupFilesDir,"launch-handoff.json").readText())
            pending.getString("session")==File(session).name&&pending.getString("attempt")==intent.getStringExtra("launchAttempt")&&
                (pending.getString("state") in listOf("started","presentation_waiting")||(pending.getString("state")=="dispatching"&&System.currentTimeMillis()<pending.getLong("expires_at")))
        }.getOrDefault(false)
        if(!validAttempt()){finish();return}
        val status = WorkspaceRepository.inside(File("/sdcard/mcpy/logs"), File(session).name + "/status.json")
        try {
            if (android.os.Build.VERSION.SDK_INT < 33) error("系统不支持直接启动画中画")
            require(packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))
            if (!launched) {
                dev.mcpywrap.workspace.LaunchEvidence.record(status.parentFile!!,"launcher","launcher_resumed")
                WorkspaceRepository.atomic(File(status.parentFile,"launch-receipt.json"),JSONObject().put("stage","launcher_resumed").put("observed_at",System.currentTimeMillis()).toString().toByteArray())
                // No NEW_TASK: Android requires source and target in the same task.
                val game = Intent().setClassName(this, "com.mojang.minecraftpe.MainActivity")
                    .putExtra("data", intent.getStringExtra("data")).putExtra("session", session)
                    .putExtra("smallWindow", true).putExtra("directPip", true).putExtra("launchAttempt",intent.getStringExtra("launchAttempt"))
                val display=android.util.DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(display)
                val ratio=maxOf(display.widthPixels,display.heightPixels).toFloat()/minOf(display.widthPixels,display.heightPixels)
                val aspect=if(ratio in 0.42f..2.39f)Rational(maxOf(display.widthPixels,display.heightPixels),minOf(display.widthPixels,display.heightPixels))else Rational(16,9)
                val options = ActivityOptions.makeLaunchIntoPip(PictureInPictureParams.Builder()
                    .setAspectRatio(aspect).setTitle(intent.getStringExtra("title") ?: "mcpy").build())
                startActivity(game, options.toBundle())
                launched = true
            }
            observing = true
            handler.post(object : Runnable {
                override fun run() {
                    if (isDestroyed || isFinishing) return
                    val value = runCatching { JSONObject(status.readText()) }.getOrNull()
                    if (value?.optBoolean("pip") == true && !callerRestored) {
                        callerRestored = true
                        // Finishing the source can dismiss the pinned child on OEM systems.
                        // Keep this transparent task behind the caller until the session ends.
                        moveTaskToBack(true)
                    }
                    if (value?.optString("phase") in listOf("saved", "failed", "interrupted") || !validAttempt()) finish()
                    else handler.postDelayed(this, if (callerRestored) 500 else 100)
                }
            })
        } catch (error: Exception) {
            WorkspaceRepository.atomic(status, JSONObject().put("phase", "failed").put("error", error.message)
                .put("code", if(error is SecurityException)"launch_blocked_by_os"else "pip_launch_failed").toString().toByteArray())
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
