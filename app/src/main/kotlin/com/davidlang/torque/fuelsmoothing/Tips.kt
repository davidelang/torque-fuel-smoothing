package com.davidlang.torque.fuelsmoothing

import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Persistent tip record. Billing connects only on Tip, or to finish a recorded tip (started, pending,
 * or purchased but not yet acknowledged/consumed) at shutdown and at the next startup.
 */
internal object TipRules {
    enum class State { STARTED, PENDING, PURCHASED }

    data class Record(val state: State, val tMs: Long, val token: String? = null)

    /** A STARTED record with no purchase found after this long was abandoned. */
    const val ABANDON_MS = 60L * 60L * 1000L

    fun needsCheck(r: Record?): Boolean = r != null

    enum class Action { ACKNOWLEDGE_AND_CONSUME, CONSUME, KEEP, CLEAR }

    /** One purchase as seen by queryPurchasesAsync: state + acknowledged. Empty list = nothing owned. */
    data class Seen(val purchased: Boolean, val pending: Boolean, val acknowledged: Boolean, val token: String)

    fun decide(r: Record?, seen: List<Seen>, nowMs: Long): List<Pair<Action, String?>> {
        if (r == null) return emptyList()
        val out = ArrayList<Pair<Action, String?>>()
        for (s in seen) {
            when {
                s.purchased && !s.acknowledged -> out.add(Action.ACKNOWLEDGE_AND_CONSUME to s.token)
                s.purchased -> out.add(Action.CONSUME to s.token)
            }
        }
        val anyPending = seen.any { it.pending }
        if (out.isEmpty()) {
            out.add(
                when {
                    anyPending -> Action.KEEP to null
                    r.state == State.STARTED && nowMs - r.tMs < ABANDON_MS -> Action.KEEP to null
                    r.state == State.PENDING -> Action.KEEP to null
                    else -> Action.CLEAR to null
                },
            )
        }
        return out
    }

    fun encode(r: Record): String = "${r.state.name}|${r.tMs}|${r.token.orEmpty()}"

    fun decode(s: String?): Record? {
        if (s.isNullOrBlank()) return null
        val p = s.split('|')
        val st = State.entries.firstOrNull { it.name == p.getOrNull(0) } ?: return null
        return Record(st, p.getOrNull(1)?.toLongOrNull() ?: 0L, p.getOrNull(2)?.takeIf { it.isNotEmpty() })
    }
}

internal object TipStore {
    private const val KEY = "tip_record"

    fun get(ctx: Context): TipRules.Record? = TipRules.decode(FixFuelPrefs.prefs(ctx).getString(KEY, null))

    fun put(ctx: Context, r: TipRules.Record) {
        FixFuelPrefs.prefs(ctx).edit().putString(KEY, TipRules.encode(r)).apply()
        FsLog.i("FuelSmooth", "tip record ${r.state}")
    }

    fun clear(ctx: Context) {
        FixFuelPrefs.prefs(ctx).edit().remove(KEY).apply()
        FsLog.i("FuelSmooth", "tip record cleared")
    }

    /** Persist the record from a purchase update (listener). */
    fun note(ctx: Context, p: Purchase) {
        val st = when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> TipRules.State.PURCHASED
            Purchase.PurchaseState.PENDING -> TipRules.State.PENDING
            else -> return
        }
        put(ctx, TipRules.Record(st, System.currentTimeMillis(), p.purchaseToken))
    }
}

/** Finishes a recorded tip: connect, queryPurchasesAsync, acknowledge/consume, clear. No-op without a record. */
internal object TipReconciler {
    @Volatile private var running = false

    fun run(ctx0: Context, why: String) {
        val ctx = ctx0.applicationContext
        val rec = TipStore.get(ctx)
        if (!TipRules.needsCheck(rec) || running) return
        running = true
        FsLog.i("FuelSmooth", "tip check ($why): record=${rec?.state}")
        val client = BillingClient.newBuilder(ctx)
            .setListener { _, _ -> }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        fun done(msg: String) {
            FsLog.i("FuelSmooth", "tip check ($why): $msg")
            runCatching { client.endConnection() }
            running = false
        }
        try {
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) return done("setup ${result.responseCode}")
                    val q = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
                    client.queryPurchasesAsync(q) { r2, purchases ->
                        if (r2.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync done("query ${r2.responseCode}")
                        val seen = purchases.map {
                            TipRules.Seen(
                                it.purchaseState == Purchase.PurchaseState.PURCHASED,
                                it.purchaseState == Purchase.PurchaseState.PENDING, it.isAcknowledged, it.purchaseToken,
                            )
                        }
                        val acts = TipRules.decide(TipStore.get(ctx), seen, System.currentTimeMillis())
                        var outstanding = acts.count { it.first == TipRules.Action.ACKNOWLEDGE_AND_CONSUME || it.first == TipRules.Action.CONSUME }
                        if (outstanding == 0) {
                            if (acts.any { it.first == TipRules.Action.CLEAR }) TipStore.clear(ctx)
                            return@queryPurchasesAsync done("actions=${acts.map { it.first }}")
                        }
                        var allOk = true
                        for ((a, token) in acts) {
                            if (token == null) continue
                            val consume = {
                                client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build()) { r3, _ ->
                                    if (r3.responseCode != BillingClient.BillingResponseCode.OK) allOk = false
                                    if (--outstanding == 0) {
                                        if (allOk) TipStore.clear(ctx)
                                        done("finished ok=$allOk")
                                    }
                                }
                            }
                            if (a == TipRules.Action.ACKNOWLEDGE_AND_CONSUME) {
                                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { consume() }
                            } else {
                                consume()
                            }
                        }
                    }
                }

                override fun onBillingServiceDisconnected() {
                    running = false
                }
            })
        } catch (t: Throwable) {
            done("start failed ${t.javaClass.simpleName}")
        }
    }
}
