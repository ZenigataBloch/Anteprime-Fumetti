package it.zenigata.anteprime

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val steps = listOf(
    "Anteprime" to "Le anteprime di Panini, Star Comics e Manicomix. Scorri a destra e a sinistra per cambiare fonte, tira verso il basso per aggiornare. Tocca una scheda per scaricarla e leggerla anche offline.",
    "Cerca un'opera" to "La lente cerca un nome nelle ultime anteprime di ogni fonte (quante, lo scegli dalle impostazioni). Se ti interessa, la aggiungi alle opere seguite.",
    "Wishlist" to "Qui trovi le note salvate dal lettore PDF e, nella seconda scheda, le opere seguite: quando esce un'anteprima con quei nomi ti arriva una notifica.",
    "Impostazioni" to "L'ingranaggio in alto a destra: tema, colori, notifiche, numero di anteprime della ricerca e aggiornamenti. Da lì puoi rivedere questo tutorial."
)

/** Mini tutorial mostrato al primo avvio (e rivedibile dalle impostazioni). */
@Composable
fun TutorialDialog(onClose: () -> Unit) {
    var i by rememberSaveable { mutableIntStateOf(0) }
    val (title, body) = steps[i]
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column {
                Text(body)
                Spacer(Modifier.height(12.dp))
                Text("${i + 1}/${steps.size}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { if (i < steps.lastIndex) i++ else onClose() }) {
                Text(if (i < steps.lastIndex) "Avanti" else "Fine")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Salta") } }
    )
}
