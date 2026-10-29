package dev.home.folddesk

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

fun createTipStore(context: Context): TipStore? = PlayTipStore(context.applicationContext)

/**
 * Tips are consumable one-time products: each one is consumed straight away,
 * so the same tip can be given again. All Play needs is the BILLING permission.
 */
private class PlayTipStore(context: Context) : TipStore, PurchasesUpdatedListener {
    private val prefs = context.getSharedPreferences("tips", Context.MODE_PRIVATE)
    private val details = mutableMapOf<String, ProductDetails>()

    override var tips by mutableStateOf(emptyList<Tip>())
        private set
    override var supported by mutableStateOf(prefs.getBoolean(SUPPORTED, false))
        private set
    override var thanked by mutableIntStateOf(0)
        private set

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingResponseCode.OK) return
                loadTips()
                // A tip paid while the app was closed (or a pending one that
                // cleared since) still needs consuming.
                val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
                client.queryPurchasesAsync(params) { _, purchases -> purchases.forEach { finish(it, thank = false) } }
            }

            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun loadTips() {
        val products = TIP_IDS.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(ProductType.INAPP).build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        client.queryProductDetailsAsync(params) { result, found ->
            if (result.responseCode != BillingResponseCode.OK) return@queryProductDetailsAsync
            found.productDetailsList.forEach { details[it.productId] = it }
            tips = TIP_IDS.mapNotNull { id ->
                val price = details[id]?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
                price?.let { Tip(id, it) }
            }
        }
    }

    override fun buy(activity: Activity, tip: Tip) {
        val product = details[tip.id] ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()))
            .build()
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingResponseCode.OK) purchases?.forEach { finish(it, thank = true) }
    }

    private fun finish(purchase: Purchase, thank: Boolean) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val params = ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        client.consumeAsync(params) { result, _ ->
            if (result.responseCode != BillingResponseCode.OK) return@consumeAsync
            if (!supported) {
                prefs.edit().putBoolean(SUPPORTED, true).apply()
                supported = true
            }
            if (thank) thanked++
        }
    }

    override fun close() = client.endConnection()

    private companion object {
        const val SUPPORTED = "supported"
    }
}
