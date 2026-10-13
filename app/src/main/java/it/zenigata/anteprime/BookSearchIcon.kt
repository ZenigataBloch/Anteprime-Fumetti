package it.zenigata.anteprime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Libro aperto con lente d'ingrandimento (si colora come ogni Icon). */
val BookSearchIcon: ImageVector by lazy {
    ImageVector.Builder("BookSearch", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
        ) {
            // pagina sinistra
            moveTo(12f, 7f); curveTo(10.5f, 5.5f, 7f, 4.5f, 2.5f, 5f); lineTo(2.5f, 17f)
            curveTo(6f, 16.8f, 9f, 17.2f, 11.3f, 18.5f)
            // pagina destra e dorso
            moveTo(12f, 7f); curveTo(13.5f, 5.5f, 17f, 4.5f, 21.5f, 5f); lineTo(21.5f, 11.5f)
            moveTo(12f, 7f); lineTo(12f, 12f)
            // lente e manico
            moveTo(20f, 16f); arcTo(4f, 4f, 0f, true, true, 12f, 16f); arcTo(4f, 4f, 0f, true, true, 20f, 16f)
            moveTo(19f, 19f); lineTo(22f, 22f)
        }
    }.build()
}
