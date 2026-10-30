package com.werd.app

import com.werd.app.data.Seed
import com.werd.app.data.WerdItem

/** For the store listing: the sample ratib is swapped for the well-known evening adhkar. */
object StoreDemo {
    const val EVENING_TITLE = "أذكار المساء"
    /** The line read aloud in the screenshots, and the word marked in it. */
    const val MARKED_WORD = "حَوْلَ"

    val evening = listOf(
        "اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ ۚ لَا تَأْخُذُهُ سِنَةٌ وَلَا نَوْمٌ" to 1,
        "قُلْ هُوَ اللَّهُ أَحَدٌ" to 3,
        "قُلْ أَعُوذُ بِرَبِّ الْفَلَقِ" to 3,
        "قُلْ أَعُوذُ بِرَبِّ النَّاسِ" to 3,
        "أَمْسَيْنَا وَأَمْسَى الْمُلْكُ لِلَّهِ، وَالْحَمْدُ لِلَّهِ، لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ" to 1,
        "أَعُوذُ بِكَلِمَاتِ اللَّهِ التَّامَّاتِ مِنْ شَرِّ مَا خَلَقَ" to 3,
        "لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ الْعَلِيِّ الْعَظِيمِ" to 10,
        "بِسْمِ اللَّهِ الَّذِي لَا يَضُرُّ مَعَ اسْمِهِ شَيْءٌ فِي الْأَرْضِ وَلَا فِي السَّمَاءِ وَهُوَ السَّمِيعُ الْعَلِيمُ" to 3,
        "حَسْبِيَ اللَّهُ لَا إِلَهَ إِلَّا هُوَ عَلَيْهِ تَوَكَّلْتُ وَهُوَ رَبُّ الْعَرْشِ الْعَظِيمِ" to 7,
        "رَضِيتُ بِاللَّهِ رَبًّا، وَبِالْإِسْلَامِ دِينًا، وَبِمُحَمَّدٍ صَلَّى اللَّهُ عَلَيْهِ وَسَلَّمَ نَبِيًّا" to 3,
        "يَا حَيُّ يَا قَيُّومُ بِرَحْمَتِكَ أَسْتَغِيثُ، أَصْلِحْ لِي شَأْنِي كُلَّهُ، وَلَا تَكِلْنِي إِلَى نَفْسِي طَرْفَةَ عَيْنٍ" to 1,
        "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ" to 100,
        "اللَّهُمَّ صَلِّ وَسَلِّمْ عَلَى نَبِيِّنَا مُحَمَّدٍ" to 10,
        "أَسْتَغْفِرُ اللَّهَ وَأَتُوبُ إِلَيْهِ" to 100,
    )

    /** Call right after seedIfNeeded. */
    suspend fun replaceRatib(app: WerdApp) {
        val dao = app.db.dao()
        val w = dao.werds().firstOrNull { it.title == Seed.RATIB_TITLE } ?: return
        app.repo.saveWerd(w.copy(title = EVENING_TITLE), evening.mapIndexed { i, (t, n) -> WerdItem(werdId = w.id, position = i, text = t, target = n) })
    }
}
