package com.werd.app

import com.werd.app.parse.WerdParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WerdParserTest {
    private fun d(s: String) = WerdParser.detect(s)

    @Test fun digitsInBrackets() {
        assertEquals(3, d("أعوذ بكلمات الله التامات من شر ما خلق (3)").count)
        assertEquals("أعوذ بكلمات الله التامات من شر ما خلق", d("أعوذ بكلمات الله التامات من شر ما خلق (3)").rest)
        assertEquals(11, d("أستغفر الله (١١)").count)
        assertEquals(41, d("لا إله إلا الله [41]").count)
    }

    @Test fun digitsWithTimesWord() {
        assertEquals(7, d("حسبنا الله ونعم الوكيل ٧ مرات").count)
        assertEquals("حسبنا الله ونعم الوكيل", d("حسبنا الله ونعم الوكيل ٧ مرات").rest)
        assertEquals(100, d("سبحان الله وبحمده (100 مرة)").count)
        assertEquals(10, d("لا حول ولا قوة إلا بالله - 10 مرّات").count)
        assertEquals(3, d("SubhanAllah 3 times").count)
    }

    @Test fun multiplicationSign() {
        assertEquals(33, d("سبحان الله ×33").count)
        assertEquals(4, d("الله أكبر x4").count)
        assertEquals(3, d("3x Astaghfirullah").count)
    }

    @Test fun arabicWords() {
        assertEquals(3, d("قل هو الله أحد ثلاث مرات").count)
        assertEquals("قل هو الله أحد", d("قل هو الله أحد ثلاث مرات").rest)
        assertEquals(2, d("اللهم صل على محمد مرتين").count)
        assertEquals(100, d("سبحان الله مائة مرة").count)
        assertEquals(11, d("صلى الله عليه وسلم إحدى عشرة مرة").count)
        assertEquals(33, d("سبحان الله ثلاثاً وثلاثين").count)
        assertEquals(7, d("حسبي الله (سبعاً)").count)
        assertEquals(10, d("أستغفر الله عشر مرات").count)
        assertEquals(1, d("الفاتحة (مرة واحدة)").count)
    }

    @Test fun ayahNumbersAreNotCounts() {
        assertNull(d("اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ ﴿٢٥٥﴾").count)
        assertNull(d("سبحان الله والحمد لله").count)
    }

    @Test fun countOnlyLineAppliesToPrevious() {
        val r = WerdParser.parse("أستغفر الله\n(١١ مرة)\nتائبون إلى الله ٣")
        assertEquals(2, r.items.size)
        assertEquals(11, r.items[0].count)
        assertEquals(1, r.items[1].count) // bare trailing digit is not a count
    }

    @Test fun titleDetection() {
        val r = WerdParser.parse("راتب الحداد\nالفاتحة\nسبحان الله (3)\nالحمد لله (3)")
        assertEquals("راتب الحداد", r.title)
        assertEquals(3, r.items.size)
    }

    @Test fun tallyHtmlImport() {
        val html = """<!DOCTYPE html><html><head><title>راتب العطاس</title></head><body><script>
            const sentences = [["أعوذ بالله", 3], ["لا إله إلا الله", 41], ["قال \"نعم\"", 1]];
            const x = [["no", 9]];
            </script></body></html>"""
        val r = WerdParser.parse(html)
        assertEquals("راتب العطاس", r.title)
        assertEquals(3, r.items.size)
        assertEquals(41, r.items[1].count)
        assertEquals("قال \"نعم\"", r.items[2].text)
        assertTrue(r.items.all { it.detected })
    }

    @Test fun dashAndTanween() {
        assertEquals(3, d("رضيت بالله رباً وبالإسلام ديناً – 3").count)
        assertEquals("رضيت بالله رباً وبالإسلام ديناً", d("رضيت بالله رباً وبالإسلام ديناً – 3").rest)
        assertEquals("حسبي الله", d("حسبي الله سبعاً").rest)
        assertEquals(7, d("حسبي الله سبعاً").count)
    }

    @Test fun paragraphWithInlineCounts() {
        // Hizb al-Bahr pasted as one long paragraph: counts sit after phrases mid-paragraph
        val t = javaClass.classLoader!!.getResource("hizb_bahr.txt").readText()
        val r = WerdParser.parse(t)
        val counted = r.items.filter { it.detected }.map { it.text.take(12) to it.count }
        assertEquals(10, counted.size)
        assertEquals(listOf(3, 3, 7, 3, 3, 3, 3, 3, 3, 3), counted.map { it.second })
        assertEquals("كَهَيعص", r.items.first { it.count == 3 }.text)
        assertEquals("حم", r.items.first { it.count == 7 }.text)
        assertTrue(r.items.none { it.text.length > 420 })          // long stretches are chunked
        assertTrue(r.items.none { it.text.contains("ثلاثاً") })    // markers removed
    }

    @Test fun phraseAndCountInOneBracket() {
        assertEquals(3, d("(كَهَيعص ثلاثاً)").count)
        assertEquals("كَهَيعص", d("(كَهَيعص ثلاثاً)").rest)
        assertEquals(7, d("[حسبي الله ٧ مرات]").count)
        assertEquals(100, d("(١٠٠ مرة)").count)
    }

    @Test fun repeatedWord() {
        assertEquals("حم" to 7, WerdParser.repeatedToken("(حم حم حم حم حم حم حم)"))
        assertNull(WerdParser.repeatedToken("يا الله يا الله"))
    }

    @Test fun lineEndCountStillAppliesToWholeLine() {
        val r = WerdParser.parse("سبحان الله وبحمده، سبحان الله العظيم (3)")
        assertEquals(1, r.items.size)
        assertEquals(3, r.items[0].count)
    }

    @Test fun countsWithoutCommasSplitTheLine() {
        // OCR'd booklet line: several adhkar on one line, separated only by their counts
        val r = WerdParser.parse("سبحان الله (33) الحمد لله (33) الله أكبر (34)")
        assertEquals(listOf(33, 33, 34), r.items.map { it.count })
        assertEquals(listOf("سبحان الله", "الحمد لله", "الله أكبر"), r.items.map { it.text })
    }

    @Test fun numbering() {
        val r = WerdParser.parse("1. سبحان الله (33)\n2. الحمد لله (33)\n3. الله أكبر (34)")
        assertEquals(listOf(33, 33, 34), r.items.map { it.count })
        assertEquals("سبحان الله", r.items[0].text)
    }
}
