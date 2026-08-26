package dev.aryan.panelkiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Everything that needs device-owner status, provisioned once over ADB:
 *
 *   adb shell dpm set-device-owner dev.aryan.panelkiosk/.AdminReceiver
 *
 * (Only possible while no accounts exist on the device. Fails harmlessly
 * otherwise — every feature here degrades gracefully without ownership.)
 */
class OwnerTools(private val ctx: Context) {
    private val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(ctx, AdminReceiver::class.java)

    val isOwner: Boolean get() = dpm.isDeviceOwnerApp(ctx.packageName)
    val isAdmin: Boolean get() = dpm.isAdminActive(admin)

    /** One-time policies that make a wall panel dependable. Safe to re-apply. */
    fun applyBasePolicies() {
        if (!isOwner) return
        // silent screen pinning: only allowlisted packages pin without the toast
        dpm.setLockTaskPackages(admin, arrayOf(ctx.packageName))
        // never let the system doze the panel while it has power
        dpm.setGlobalSetting(
            admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
            (1 or 2 or 4).toString() // AC | USB | wireless
        )
    }

    /** With the status bar disabled, a pinned kiosk can't be swiped out of. */
    fun setStatusBarDisabled(disabled: Boolean) {
        if (isOwner) runCatching { dpm.setStatusBarDisabled(admin, disabled) }
    }

    /** Fully-API `rebootDevice`: real reboot, no root — device owner only. */
    fun reboot(): Boolean {
        if (!isOwner) return false
        return runCatching { dpm.reboot(admin) }.isSuccess
    }

    fun lockNow(): Boolean {
        if (!isAdmin) return false
        return runCatching { dpm.lockNow() }.isSuccess
    }

    /** Escape hatch: give up ownership so the app can be uninstalled normally. */
    fun releaseOwnership() {
        if (isOwner) runCatching {
            setStatusBarDisabled(false)
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(ctx.packageName)
        }
    }
}
