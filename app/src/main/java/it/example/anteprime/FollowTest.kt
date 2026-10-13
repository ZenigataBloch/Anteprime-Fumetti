package it.example.anteprime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Prova delle opere seguite su un'anteprima qualsiasi della lista: la scarica se manca, legge il testo (OCR, con cache)
 * e cerca i nomi con KeywordSearch, come fa FollowWorker. Usa la stessa coda OCR di Indexer (una lettura alla volta).
 */
object FollowTest {
    suspend fun check(repo: Repo, p: Preview, names: List<String>): List<Hit> {
        if (p.id !in repo.localIds()) withContext(Dispatchers.IO) { repo.download(p) }
        val file = repo.file(p)
        return Indexer.exclusive {
            val done = withContext(Dispatchers.IO) { PageText.load(file) }
            val pages = HashMap<Int, List<PageLine>>(done)
            PageText.build(file, done, onPage = { i, lines -> pages[i] = lines })
            withContext(Dispatchers.Default) { KeywordSearch.search(pages, names, p.source) }
        }
    }
}
