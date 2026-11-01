package com.mlmvpn.scanner.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home screen search's matching rules: one spelling for every way Persian gets typed, and the order. */
class SearchMatcherTest {

    private fun n(s: String) = SearchMatcher.normalize(s)

    private fun rank(query: String, vararg names: String): List<String> =
        SearchMatcher.rank(query, names.toList()) { listOf(SearchMatcher.Field(it)) }

    private fun matches(query: String, name: String) = rank(query, name).isNotEmpty()

    @Test
    fun arabicYehAndKafAreThePersianOnes() {
        assertEquals(n("کانفیگ"), n("كانفيگ"))
        assertEquals(n("ی"), n("ى"))
        assertEquals(n("پایین"), n("پائین"))
        assertTrue(matches("سايفون", "سایفون"))
        assertTrue(matches("كانفيگ رايگان", "کانفیگ رایگان"))
    }

    @Test
    fun hehAndAlefSpellingsFold() {
        assertEquals(n("صفحه اصلی"), n("صفحهٔ اصلی"))
        assertEquals(n("صفحه"), n("صفحۀ"))
        assertEquals(n("صفحه"), n("صفحة"))
        assertEquals(n("اپدیت"), n("آپدیت"))
        assertEquals(n("امنیت"), n("أمنیت"))
        assertEquals(n("اسلامی"), n("إسلامی"))
        assertEquals(n("مومن"), n("مؤمن"))
        assertTrue(matches("اموزش", "آموزش‌ها"))
        assertTrue(matches("آپدیت", "اپدیت خودکار"))
    }

    @Test
    fun zeroWidthNonJoinerKashidaAndVowelMarksVanish() {
        assertEquals(n("آموزشها"), n("آموزش‌ها"))
        assertEquals(n("تنظیمات"), n("تنظــیمات"))
        assertEquals(n("تنظیمات"), n("تَنظیماتِ"))
        assertEquals(n("ab"), n("a‏b"))
        assertTrue(matches("آموزشها", "آموزش‌ها"))
        assertTrue(matches("آموزش‌ها", "آموزشها"))
        assertTrue(matches("گیت هاب", "گیت‌هاب تانل"))
        assertTrue(matches("گیتهاب", "گیت هاب"))
    }

    @Test
    fun digitsOfEveryScriptAreOne() {
        assertEquals("123", n("۱۲۳"))
        assertEquals("123", n("١٢٣"))
        assertEquals("123", n("123"))
        assertTrue(matches("ipv۴", "IPv4"))
        assertTrue(matches("IPv٤", "IPv4"))
        assertTrue(matches("warp2", "WARP²"))
        assertTrue(matches("۱۲۸۰", "MTU 1280"))
    }

    @Test
    fun latinCaseAndAccentsDoNotMatter() {
        assertEquals(n("wireguard"), n("WireGuard"))
        assertTrue(matches("WIREGUARD", "WireGuard"))
        assertTrue(matches("wire", "WireGuard"))
        assertTrue(matches("cafe", "Café"))
    }

    @Test
    fun punctuationAndSpacesSplitWords() {
        assertEquals(listOf("wi", "fi"), SearchMatcher.tokens("Wi-Fi"))
        assertEquals(listOf("تنظیمات", "شبکه"), SearchMatcher.tokens("  تنظیمات ›  شبکه "))
        assertEquals(listOf("تونل", "برنامه", "ها"), SearchMatcher.tokens("(تونل / برنامه ها)"))
        assertTrue(SearchMatcher.tokens("  ‌ ").isEmpty())
        assertTrue(matches("wifi", "Wi-Fi"))
        assertTrue(matches("wi fi", "Wi-Fi"))
    }

    @Test
    fun aNameThatStartsWithTheQueryComesBeforeOneThatContainsIt() {
        assertEquals(listOf("Scanner", "Mascot"), rank("sc", "Mascot", "Scanner"))
        assertEquals(listOf("پروکسی تلگرام", "حالت پروکسی"), rank("پروکسی", "حالت پروکسی", "پروکسی تلگرام"))
        // A word that starts with it beats a word that only contains it.
        assertEquals(listOf("مرور اسکنر", "مرور بکاسکن"), rank("اسکن", "مرور بکاسکن", "مرور اسکنر"))
    }

    @Test
    fun theExactNameComesFirstAndTheShorterOfTwoEqualMatches() {
        assertEquals(listOf("وارپ", "وارپ در وارپ"), rank("وارپ", "وارپ در وارپ", "وارپ"))
        assertEquals(listOf("Tor", "Torrent"), rank("tor", "Torrent", "Tor"))
    }

    @Test
    fun equalMatchesKeepTheirOrder() {
        assertEquals(listOf("ماسک", "مادر"), rank("ما", "ماسک", "مادر"))
        assertEquals(listOf("مادر", "ماسک"), rank("ما", "مادر", "ماسک"))
    }

    @Test
    fun everyWordOfTheQueryHasToBeFound() {
        assertEquals(
            listOf("تنظیمات VPN"),
            rank("vpn تنظیمات", "تنظیمات نمایش", "تنظیمات VPN", "VPN همیشه روشن"),
        )
        assertTrue(rank("وارپ گوگل", "وارپ", "گوگل اسکریپت").isEmpty())
        assertTrue(rank("", "وارپ").isEmpty())
        assertTrue(rank("   ", "وارپ").isEmpty())
    }

    @Test
    fun theWordsMayComeFromDifferentNamesOfOneResult() {
        val fields = listOf(SearchMatcher.Field("تنظیمات"), SearchMatcher.Field("Settings"))
        val both = SearchMatcher.score(SearchMatcher.tokens("تنظیمات settings"), fields)
        val one = SearchMatcher.score(SearchMatcher.tokens("تنظیمات"), fields)
        assertTrue(both > 0)
        assertTrue(one > both)
    }

    @Test
    fun oneLetterFindsOnlyWordsThatStartWithIt() {
        assertEquals(listOf("تور", "وارپ تو وارپ"), rank("ت", "مستقیم", "تور", "وارپ تو وارپ"))
        // Two letters may be found inside a word.
        assertEquals(listOf("مستقیم"), rank("تق", "مستقیم", "تور"))
    }

    @Test
    fun aMatchInAWeakerFieldCountsForLess() {
        val t = SearchMatcher.tokens("ابزار")
        val name = listOf(SearchMatcher.Field("ابزارها"))
        val inFolder = listOf(SearchMatcher.Field("آیپی ثابت"), SearchMatcher.Field("ابزارها", weight = 0.45f))
        assertTrue(SearchMatcher.score(t, inFolder) > 0)
        assertTrue(SearchMatcher.score(t, name) > SearchMatcher.score(t, inFolder))
    }

    @Test
    fun highlightsAreWholeWordsOfTheOriginalText() {
        assertEquals(listOf(11..14), SearchMatcher.highlights("اتصال شبکه محلی", "محل"))
        // The zero-width non-joiner inside the word stays inside the bold.
        assertEquals(listOf(0..7), SearchMatcher.highlights("آموزش‌ها و راهنما", "اموزشها"))
        assertEquals(listOf(3..4, 6..9), SearchMatcher.highlights("Wi-Fi auto download", "auto fi"))
        assertEquals(listOf(0..5), SearchMatcher.highlights("سایفون", "سايف"))
        assertTrue(SearchMatcher.highlights("سایفون", "تور").isEmpty())
    }
}
