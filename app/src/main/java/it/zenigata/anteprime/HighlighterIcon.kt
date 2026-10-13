package it.zenigata.anteprime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Evidenziatore: corpo del pennarello inclinato, punta a scalpello e striscia evidenziata sotto. */
val HighlighterIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Highlighter",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(fill = SolidColor(Color.Black)) {
        // corpo
        moveTo(15f, 3f); lineTo(21f, 9f); lineTo(12f, 18f); lineTo(6f, 12f); close()
        // punta
        moveTo(5f, 13f); lineTo(11f, 19f); lineTo(7f, 20f); lineTo(4f, 17f); close()
        // striscia
        moveTo(2f, 22f); lineTo(22f, 22f); lineTo(22f, 24f); lineTo(2f, 24f); close()
    }.build()
}
