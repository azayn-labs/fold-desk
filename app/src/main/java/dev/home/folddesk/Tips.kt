package dev.home.folddesk

import android.app.Activity

/** One tip size, with the price Google Play shows in the user's currency. */
class Tip(val id: String, val price: String)

/**
 * Optional tips through Google Play billing. Nothing in FoldDesk is locked
 * behind them. Only the Play build has a store: the GitHub build links to
 * Ko-fi instead, so its [createTipStore] returns null.
 */
interface TipStore {
    /** Tip sizes with prices, in [TIP_IDS] order; empty until Play answers. */
    val tips: List<Tip>
    /** True once any tip went through, so settings can say thanks. */
    val supported: Boolean
    /** Goes up each time a tip completes, to show the thank-you sheet. */
    val thanked: Int
    fun buy(activity: Activity, tip: Tip)
    fun close()
}

/** Product ids as set up in Play Console, smallest first. */
val TIP_IDS = listOf("tip_coffee", "tip_lunch", "tip_great_day")
