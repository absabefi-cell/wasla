package com.absabefi.wasla

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Relance la connexion apres un redemarrage du telephone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Hub.init(context)
        if (Hub.hasToken()) ChatService.start(context)
    }
}
