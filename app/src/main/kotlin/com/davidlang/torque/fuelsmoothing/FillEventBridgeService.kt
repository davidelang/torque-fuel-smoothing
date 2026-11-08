package com.davidlang.torque.fuelsmoothing

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.davidlang.torque.fuelsmoothing.fillbridge.IFillEventBridge

/** Open data-only Binder endpoint. No connection to PluginService, Torque or live fill accounting. */
class FillEventBridgeService : Service() {
    private val endpoint = object : IFillEventBridge.Stub() {
        override fun getCapabilities(): String = FillBridgeProtocol.capabilities(BuildConfig.VERSION_NAME)

        override fun submitFillBatch(batchJson: String?): String {
            if (batchJson == null) return FillBridgeProtocol.response(null, null, status = "INVALID_REQUEST")
            val batch = try {
                FillBridgeProtocol.parse(batchJson)
            } catch (e: FillBridgeProtocol.EnvelopeError) {
                return FillBridgeProtocol.response(e.batchId, null, status = e.status)
            }
            // No input/exception text in logs or ACKs, including VIN and evidence references.
            return FillBridgeStore.submit(filesDir, batch)
        }
    }

    override fun onBind(intent: Intent?): IBinder = endpoint
}
