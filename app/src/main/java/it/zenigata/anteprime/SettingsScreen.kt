package it.zenigata.anteprime

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Contenuti FAQ: per aggiungere una voce basta aggiungere un Faq(...) alla lista
// ---------------------------------------------------------------------------
private data class Faq(val question: String, val answer: String)

private val featuresFaq = listOf(
    Faq(
        "Da dove arrivano le anteprime?",
        "Da tre fonti: Panini, Star Comics e Manicomix. La lista ha una scheda \"Tutti\" e una per ogni fonte: " +
            "tocca i chip in alto o scorri lateralmente. Tira verso il basso per aggiornare."
    ),
    Faq(
        "Come leggo e scarico un'anteprima?",
        "Tocca la scheda: l'anteprima viene scaricata e si apre nel lettore. Da quel momento resta sul telefono " +
            "e puoi leggerla offline. Quella di Manicomix, essendo un catalogo molto lungo, si scarica soltanto " +
            "e non si apre da sola. Riaprendola riparti dall'ultima pagina letta."
    ),
    Faq(
        "Come funziona il lettore?",
        "Zoom con il pizzico a due dita o con doppio tap (alterna zoom normale e ingrandito sul punto toccato). " +
            "Mentre scorri compare una barra laterale: trascinala per saltare a una pagina, con l'indicatore \"pag. X / N\"."
    ),
    Faq(
        "Come cerco una parola dentro un PDF?",
        "Con la lente nel lettore. Scrivi una o più parole separate da virgola. La prima volta l'app legge il testo " +
            "di tutte le pagine (può richiedere un po'), poi i risultati sono immediati. Tocca un risultato per " +
            "aprire quella pagina, oppure la matita per trasformarlo in una nota."
    ),
    Faq(
        "Come salvo un passaggio come nota?",
        "Nel lettore attiva l'evidenziatore e trascina un rettangolo sul testo: l'app lo riconosce e te lo propone " +
            "come nota. Prima di salvare puoi correggerlo, aggiungere tag e indicare la data di uscita. " +
            "Formato, colore e mesi citati nel testo compaiono solo come tag suggeriti, da applicare toccandoli."
    ),
    Faq(
        "Cos'è la Wishlist?",
        "È la raccolta delle tue note, filtrabili per tag. Toccando una nota si apre il PDF alla sua pagina " +
            "(se non è sul telefono viene scaricato). Dalla Wishlist puoi creare note a mano, modificarle, " +
            "eliminarle e gestire i tag."
    ),
    Faq(
        "Come condivido le note?",
        "Con il pulsante condividi in alto nella Wishlist: puoi inviare tutte le note o solo quelle selezionate. " +
            "Scegli l'app di destinazione (WhatsApp, Telegram, Discord o testo semplice): cambia la formattazione. " +
            "Il testo è raggruppato per tag e, se supera il limite dell'app, viene diviso in più messaggi."
    ),
    Faq(
        "Cosa sono le opere seguite?",
        "Scrivi il nome di una serie: quando esce una nuova anteprima, l'app la scarica in background, ne legge " +
            "il testo e, se trova il nome, ti manda una notifica che apre il PDF alla pagina giusta. " +
            "Il controllo avviene circa ogni 12 ore, solo con Wi-Fi e batteria non scarica. " +
            "Le anteprime già presenti quando aggiungi la prima opera non vengono scaricate: per quelle usa la ricerca."
    ),
    Faq(
        "Quando ricevo le notifiche?",
        "Con \"Notifiche nuove anteprime\" attivo, l'app controlla le novità circa ogni 6 ore (serve la rete) " +
            "e ti avvisa se ce ne sono. Le notifiche delle opere seguite funzionano anche con questa opzione spenta. " +
            "Su Android 13 o superiore l'app chiede il permesso la prima volta."
    ),
    Faq(
        "Cosa sono i colori dinamici?",
        "Su Android 12 o superiore l'app può adottare i colori del tuo sfondo. " +
            "Su versioni precedenti l'opzione non compare."
    ),
)

private val commandsFaq = listOf(
    Faq(
        "Come cerco un'opera nelle ultime uscite?",
        "Tocca la lente in alto nella lista, scrivi il nome (o più nomi separati da virgola) e avvia la ricerca. " +
            "L'app cerca nelle ultime anteprime di ogni fonte. Dal risultato apri il PDF alla pagina trovata; " +
            "da lì, \"indietro\" riporta all'elenco dei risultati."
    ),
    Faq(
        "Come seguo una nuova opera?",
        "Due modi: dalla ricerca, con \"Aggiungi alle opere seguite\"; oppure dalla Wishlist, scheda " +
            "\"Opere seguite\", scrivendo il nome e premendo +. Per toglierla, premi la X accanto al nome."
    ),
    Faq(
        "Come provo una ricerca sulle opere seguite?",
        "Nella scheda \"Opere seguite\" usa il pulsante \"Prova sulle ultime anteprime per fonte\": " +
            "controlla subito se i tuoi nomi compaiono. Puoi fermare la prova quando vuoi."
    ),
    Faq(
        "Come vedo solo le anteprime scaricate?",
        "Tocca l'icona di download in alto nella lista: si colora quando il filtro è attivo."
    ),
    Faq(
        "Come libero spazio?",
        "Sulle schede già scaricate tocca il cestino per eliminare il PDF dal telefono."
    ),
    Faq(
        "Come cambio il numero di anteprime nella ricerca?",
        "In \"Anteprime nella ricerca\" usa − e +: da 1 a 10 anteprime più recenti per fonte (di base 3). " +
            "Più sono, più la ricerca è completa ma lenta."
    ),
    Faq(
        "Come gestisco i tag?",
        "Nella Wishlist tocca l'icona etichetta in alto, oppure \"Gestisci\" mentre scrivi una nota. " +
            "Puoi creare tag e cancellarli: cancellare un tag lo toglie dalle note, che restano."
    ),
    Faq(
        "Come torno indietro?",
        "Il tasto indietro chiude prima il PDF, poi la Wishlist o le impostazioni, e infine esce dall'app. " +
            "Nella Wishlist, se hai delle note selezionate, indietro annulla prima la selezione."
    ),
    Faq(
        "Come aggiorno l'app?",
        "Tocca \"Controlla aggiornamenti\": se esiste una versione più recente compare un avviso con " +
            "\"Scarica e installa\". Sotto la voce vedi la versione attuale."
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: VM, onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Impostazioni") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Text("Tema", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            ThemeMode.values().forEach { m ->
                ListItem(
                    headlineContent = { Text(m.label) },
                    leadingContent = { RadioButton(selected = vm.themeMode == m, onClick = null) },
                    modifier = Modifier.clickable { vm.setTheme(m) }
                )
            }
            if (Build.VERSION.SDK_INT >= 31) {
                ListItem(
                    headlineContent = { Text("Colori dinamici") },
                    trailingContent = { Switch(checked = vm.dynamicColor, onCheckedChange = null) },
                    modifier = Modifier.clickable { vm.setDynamic(!vm.dynamicColor) }
                )
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Notifiche nuove anteprime") },
                trailingContent = { Switch(checked = vm.notifyEnabled, onCheckedChange = null) },
                modifier = Modifier.clickable { vm.setNotify(!vm.notifyEnabled) }
            )
            ListItem(
                headlineContent = { Text("Anteprime nella ricerca") },
                supportingContent = { Text("Per fonte, le più recenti") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.setSearchCount(vm.searchCount - 1) }) { Icon(Icons.Default.Remove, "Meno") }
                        Text("${vm.searchCount}")
                        IconButton(onClick = { vm.setSearchCount(vm.searchCount + 1) }) { Icon(Icons.Default.Add, "Più") }
                    }
                }
            )
            ListItem(
                headlineContent = { Text("Controlla aggiornamenti") },
                supportingContent = { Text("Versione ${BuildConfig.VERSION_NAME}") },
                modifier = Modifier.clickable { vm.checkUpdate(true) }
            )

            HorizontalDivider()
            FaqSection("FAQ · Funzionalità", featuresFaq)
            FaqSection("FAQ · Comandi", commandsFaq)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FaqSection(title: String, items: List<Faq>) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    items.forEach { FaqItem(it) }
}

@Composable
private fun FaqItem(faq: Faq) {
    var expanded by rememberSaveable(faq.question) { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(faq.question) },
        supportingContent = if (expanded) ({ Text(faq.answer) }) else null,
        trailingContent = {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Comprimi" else "Espandi"
            )
        },
        modifier = Modifier.clickable { expanded = !expanded }
    )
}
