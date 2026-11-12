package com.noop.analytics

import com.noop.R
import com.noop.ui.uiStringOr
import com.noop.data.DailyMetric

// CoachSuggestions.kt — contextual coaching prompt chips derived from the user's own bands.
//
// Pure + deterministic so it is unit-testable without a strap, network, or device, and so the
// output is byte-identical to the Swift twin `StrandAnalytics.CoachSuggestions` (the cross-platform
// parity contract: the same inputs produce the same chip strings on both platforms). Reads only
// DailyMetric fields that already live on-device; no new egress, no telemetry.

object CoachSuggestions {

    /** The stable generic set used when there is no usable data for today. Byte-identical to the
     *  Swift `fallback` list. */
    val fallback: List<String> = listOf(
        uiStringOr(R.string.de_how_s_my_recovery_trending_this_week_b1a576, "How's my recovery trending this week?"),
        uiStringOr(R.string.de_what_should_today_s_training_look_like_d28e0c, "What should today's training look like?"),
        "Analyse my sleep",
        uiStringOr(R.string.de_why_am_i_run_down_c26e05, "Why am I run down?"),
    )

    /** A stable generic prompt always appended as the last chip so there is a consistent entry point
     *  even when the contextual chips already cover the user's situation. */
    private const val stableGeneric = "Analyse my sleep"

    // Charge band cutoffs (mirror the autoregulation bands in the coach system prompt).
    private const val CHARGE_RED_CUTOFF = 34.0
    private const val CHARGE_GREEN_CUTOFF = 67.0
    /** HRV "trending down": today's HRV below 85% of the trailing-30-day baseline (excluding today). */
    private const val HRV_DOWN_RATIO = 0.85
    /** Sleep "poor night": under 6h (360 min). */
    private const val POOR_SLEEP_MIN = 360.0
    /** "Already loaded" cutoff, on WHOOP's 0–21 axis. Compared through
     *  [StrainScorer.effortValueFromWhoopStrain] because stored Effort is 0–100; kept a `const val`
     *  literal so both platforms hold the same number. */
    private const val HIGH_STRAIN = 14.0
    /** Max chips surfaced. */
    private const val MAX_CHIPS = 4

    /**
     * Build 2–4 contextual prompt chips from today's bands, falling back to [fallback] when there is
     * no usable data. [recent] is oldest→newest and is used for the trailing-30-day HRV baseline.
     *
     * Byte-twin of `StrandAnalytics.CoachSuggestions.suggestions(for:recent:)`. If you change a chip
     * string here, change it in the Swift twin in the same PR.
     */
    fun suggestions(today: DailyMetric?, recent: List<DailyMetric>): List<String> {
        if (today == null) return fallback
        val charge = today.recovery
        val hrv = today.avgHrv
        val sleep = today.totalSleepMin
        val strain = today.strain
        // No usable signal at all → generic fallback.
        if (charge == null && hrv == null && sleep == null && strain == null) return fallback

        val chips = mutableListOf<String>()

        // 1. Charge band → one readiness prescription chip.
        if (charge != null) {
            chips += when {
                charge < CHARGE_RED_CUTOFF -> uiStringOr(R.string.de_active_recovery_only_today_what_should_i_939c6f, "Active recovery only today — what should I do?")
                charge < CHARGE_GREEN_CUTOFF -> uiStringOr(R.string.de_quality_over_volume_today_plan_my_sessio_083112, "Quality over volume today — plan my session")
                else -> uiStringOr(R.string.de_green_light_how_hard_can_i_push_today_aa1f8a, "Green light — how hard can I push today?")
            }
        }

        // 2. HRV trending down vs trailing-30-day baseline (excluding today).
        if (hrv != null) {
            val baseline = hrvBaseline(recent, excluding = today.day)
            if (baseline != null && baseline > 0 && hrv < HRV_DOWN_RATIO * baseline) {
                chips += uiStringOr(R.string.de_why_is_my_hrv_trending_down_2575b1, "Why is my HRV trending down?")
            }
        }

        // 3. Poor sleep (< 6h).
        if (sleep != null && sleep < POOR_SLEEP_MIN) {
            chips += uiStringOr(R.string.de_i_slept_poorly_how_do_i_recover_today_26dbde, "I slept poorly — how do I recover today?")
        }

        // 4. Already a high-strain day.
        if (strain != null && strain >= StrainScorer.effortValueFromWhoopStrain(HIGH_STRAIN)) {
            chips += uiStringOr(R.string.de_have_i_done_enough_today_or_push_more_de6c37, "Have I done enough today, or push more?")
        }

        // Signals present but none of the band conditions fired → generic fallback (avoids a lone
        // stable chip when there's nothing contextual to say).
        if (chips.isEmpty()) return fallback
        // Cap the CONTEXTUAL chips at MAX_CHIPS−1 so the stable generic always survives as the last
        // chip (a consistent entry point), then append it. Total never exceeds MAX_CHIPS.
        return chips.take(MAX_CHIPS - 1) + stableGeneric
    }

    /** Mean of [avgHrv] over the trailing 30 days of [recent], excluding [excludingDay].
     *  Returns null when no qualifying night has an HRV value. Pure; byte-twin of the Swift helper. */
    private fun hrvBaseline(recent: List<DailyMetric>, excluding: String): Double? {
        val values = recent.takeLast(30).filter { it.day != excluding }
            .mapNotNull { it.avgHrv }
        if (values.isEmpty()) return null
        return values.sum() / values.size
    }
}
