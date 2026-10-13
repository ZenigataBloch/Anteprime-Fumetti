package it.zenigata.anteprime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Prova delle opere seguite su un'anteprima qualsiasi della lista: [download] la scarica se manca,
 * [search] legge il testo (OCR, con cache) e cerca i nomi con KeywordSearch, come fa FollowWorker.
 * L'OCR usa la stessa coda di Indexer (una lettura alla volta in tutta l'app).
 */
object FollowTest {
    suspend fun download(repo: Repo, p: Preview) {
        if (p.id !in repo.localIds()) withContext(Dispatchers.IO) { repo.download(p) }
    }

    suspend fun search(repo: Repo, p: Preview, names: List<String>): List<Hit> {
        val file = repo.file(p)
        return Indexer.exclusive {
            val done = withContext(Dispatchers.IO) { PageText.load(file) }
            val pages = HashMap<Int, List<PageLine>>(done)
            PageText.build(file, done, onPage = { i, lines -> pages[i] = lines })
            withContext(Dispatchers.Default) { KeywordSearch.search(pages, names, p.source) }
        }
    }
}
