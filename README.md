# Anteprime Fumetti (Android)
Apri la cartella in Android Studio (Koala o successivo), lascia che sincronizzi Gradle (genera da solo il wrapper) e premi Run.
- Panini / Star Comics: lo scraper trova i PDF, "Scarica e leggi" li salva in filesDir/pdf e li apre col lettore integrato (PdfRenderer).
- Manicomix: i cataloghi sono su Issuu, non c'è PDF diretto -> aperti in WebView (solo online).
