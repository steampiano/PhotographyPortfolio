package com.siliconprime.tabletmirror.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.siliconprime.tabletmirror.R
import com.siliconprime.tabletmirror.net.PairingGate

/**
 * The comparison prompt, shown identically on both tablets.
 *
 * The wording deliberately makes comparing the two screens the action being
 * confirmed, not merely "do you allow this". The whole security of first pairing
 * rests on a person actually looking at the other tablet, so "Codes match" is the
 * only affirmative wording offered — there is no bare "OK" to reflexively tap.
 */
object PairingDialog {

    fun show(
        activity: Activity,
        request: PairingGate.Request,
        gate: PairingGate,
    ): AlertDialog {
        val padding = (activity.resources.displayMetrics.density * 24).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )

            addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.pairing_prompt, request.peerName)
                },
            )
            addView(
                TextView(activity).apply {
                    text = formatSas(request.sas)
                    textSize = 40f
                    typeface = android.graphics.Typeface.MONOSPACE
                    letterSpacing = 0.25f
                    setPadding(0, padding / 2, 0, padding / 2)
                },
            )
            addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.pairing_fingerprint, request.fingerprint)
                    textSize = 12f
                },
            )
        }

        return AlertDialog.Builder(activity)
            .setTitle(R.string.pairing_title)
            .setView(content)
            .setPositiveButton(R.string.pairing_match) { _, _ -> gate.respond(accept = true) }
            .setNegativeButton(R.string.pairing_mismatch) { _, _ -> gate.respond(accept = false) }
            .setCancelable(false)
            .show()
    }

    /** "482913" reads far more reliably as "482 913" when compared by eye. */
    fun formatSas(sas: String): String =
        if (sas.length == 6) "${sas.take(3)} ${sas.drop(3)}" else sas
}
