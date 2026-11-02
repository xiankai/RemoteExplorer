package com.bespectacledcyborg.remoteexplorer.utils

object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val loose = compareLoosely(a, b)
        return if (loose != 0) loose else a.compareTo(b)
    }

    private fun compareLoosely(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val endA = digitRunEnd(a, i)
                val endB = digitRunEnd(b, j)
                val byValue = compareDigitRuns(a, i, endA, b, j, endB)
                if (byValue != 0) return byValue
                i = endA
                j = endB
            } else {
                val byChar = a[i].folded().compareTo(b[j].folded())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun compareDigitRuns(a: String, startA: Int, endA: Int, b: String, startB: Int, endB: Int): Int {
        val significantA = skipZeros(a, startA, endA)
        val significantB = skipZeros(b, startB, endB)
        val byLength = (endA - significantA).compareTo(endB - significantB)
        if (byLength != 0) return byLength
        for (offset in 0 until endA - significantA) {
            val byDigit = a[significantA + offset].compareTo(b[significantB + offset])
            if (byDigit != 0) return byDigit
        }
        return 0
    }

    private fun digitRunEnd(s: String, start: Int): Int {
        var end = start
        while (end < s.length && s[end].isAsciiDigit()) end++
        return end
    }

    private fun skipZeros(s: String, start: Int, end: Int): Int {
        var at = start
        while (at < end && s[at] == '0') at++
        return at
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun Char.folded() = uppercaseChar().lowercaseChar()
}
