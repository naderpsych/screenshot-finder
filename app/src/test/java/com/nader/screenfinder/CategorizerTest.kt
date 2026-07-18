package com.nader.screenfinder

import com.nader.screenfinder.scan.Categorizer
import com.nader.screenfinder.scan.Ocr
import com.nader.screenfinder.scan.Scanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategorizerTest {
    @Test
    fun recipeHebrew() {
        val (cat, _) = Categorizer.categorize("Chrome", "רשימת מצרכים: כוס קמח, 2 ביצים", emptyList(), emptyList())
        assertEquals("מתכונים", cat)
    }

    @Test
    fun recipeFrench() {
        val (cat, _) = Categorizer.categorize(null, "Recette de quiche lorraine, ingredients: farine", emptyList(), emptyList())
        assertEquals("מתכונים", cat)
    }

    @Test
    fun chatFromWhatsApp() {
        val (cat, _) = Categorizer.categorize("WhatsApp", "היי מה קורה", emptyList(), emptyList())
        assertEquals("שיחות", cat)
    }

    @Test
    fun articleBySite() {
        val (cat, src) = Categorizer.categorize("Chrome", "כותרת כלשהי haaretz.co.il עוד טקסט", emptyList(), emptyList())
        assertEquals("כתבות", cat)
        assertEquals("הארץ", src)
    }

    @Test
    fun foodByLabelWhenNoText() {
        val (cat, _) = Categorizer.categorize(null, "", listOf("Food", "Dessert"), emptyList())
        assertEquals("אוכל", cat)
    }

    @Test
    fun psychologyAcceptanceIsNotReceipt() {
        val (cat, _) = Categorizer.categorize(
            "Facebook", "קבלה לתואר שני בפסיכולוגיה קלינית - כל מה שצריך לדעת",
            emptyList(), emptyList()
        )
        assertEquals("לימודים", cat)
    }

    @Test
    fun realReceiptStillDetected() {
        val (cat, _) = Categorizer.categorize(
            null, "קבלה מס 1234 סהכ לתשלום 89 שח", emptyList(), emptyList()
        )
        assertEquals("קבלות וקניות", cat)
    }

    @Test
    fun wordMatchRespectsBoundaries() {
        org.junit.Assert.assertTrue(Categorizer.wordMatch("קבוצת פצי החדשה", "פצי"))
        org.junit.Assert.assertFalse(Categorizer.wordMatch("נגרמה פציעה קשה", "פצי"))
        org.junit.Assert.assertFalse(Categorizer.wordMatch("הפציץ את המבחן", "פצי"))
    }

    @Test
    fun normStripsNiqqud() {
        assertEquals("שלום", Ocr.norm("שָׁלוֹם"))
    }

    @Test
    fun filenameParsing() {
        assertEquals("Chrome", Scanner.sourceApp("Screenshot_20240115_143022_Chrome.jpg"))
        assertEquals("One UI Home", Scanner.sourceApp("Screenshot_20240115_143022_One UI Home.jpg"))
        assertNull(Scanner.sourceApp("IMG_1234.jpg"))
    }
}
