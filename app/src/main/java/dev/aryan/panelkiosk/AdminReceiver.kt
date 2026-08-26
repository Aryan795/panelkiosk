package dev.aryan.panelkiosk

import android.app.admin.DeviceAdminReceiver

/** Enables DevicePolicyManager.lockNow() for the optional true-screen-off mode. */
class AdminReceiver : DeviceAdminReceiver()
