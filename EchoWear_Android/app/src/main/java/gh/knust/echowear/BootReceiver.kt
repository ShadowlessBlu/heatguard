package gh.knust.echowear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import gh.knust.echowear.service.MonitorService

/**
 * A fall-detection app that stops working after a reboot until someone opens
 * it is not doing its job, so monitoring restarts with the phone.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val service = Intent(context, MonitorService::class.java)
            .setAction(MonitorService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}
