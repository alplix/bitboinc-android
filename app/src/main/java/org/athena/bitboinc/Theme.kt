package org.athena.bitboinc

import android.content.Context
import android.graphics.Color
import edu.berkeley.boinc.R

enum class Deco { STARS, SAKURA, NEBULA, NONE }

class Palette(
    val id: String, val nameRes: Int, val bg: Int, val card: Int, val line: Int, val text: Int,
    val muted: Int, val accent: Int, val accent2: Int, val deco: Deco, val light: Boolean
)

object Themes {
    private fun c(s: String) = Color.parseColor(s)
    val all = listOf(
        Palette("cosmic", R.string.bb_theme_cosmic, c("#12091F"), c("#1E1233"), c("#33224F"), c("#F1EAFF"), c("#A597C4"), c("#FF9F43"), c("#9B6DFF"), Deco.STARS, false),
        Palette("sakura", R.string.bb_theme_sakura, c("#F6ECF6"), c("#FFFFFF"), c("#EAD7EA"), c("#3A2147"), c("#8D7499"), c("#E0549A"), c("#8E5CC9"), Deco.SAKURA, true),
        Palette("nebula", R.string.bb_theme_nebula, c("#07162B"), c("#0E2644"), c("#173A63"), c("#E4F2FF"), c("#86A5C6"), c("#27D3C4"), c("#E052C7"), Deco.NEBULA, false),
        Palette("night", R.string.bb_theme_night, c("#000000"), c("#111111"), c("#222222"), c("#EEEEEE"), c("#8A8A8A"), c("#C9B6FF"), c("#C9B6FF"), Deco.NONE, false),
        Palette("day", R.string.bb_theme_day, c("#F7F5FB"), c("#FFFFFF"), c("#E3DFF0"), c("#1D1A2B"), c("#6C6880"), c("#6D4FD1"), c("#E0872E"), Deco.NONE, true)
    )

    fun current(ctx: Context): Palette {
        val id = ctx.getSharedPreferences("bb", Context.MODE_PRIVATE).getString("theme", "cosmic")
        return all.firstOrNull { it.id == id } ?: all[0]
    }

    fun set(ctx: Context, id: String) {
        ctx.getSharedPreferences("bb", Context.MODE_PRIVATE).edit().putString("theme", id).apply()
    }
}
