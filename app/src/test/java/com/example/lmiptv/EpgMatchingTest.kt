package com.example.lmiptv

import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMatchingTest {
    @Test
    fun technicalQualityVariantsShareTheSameEpgAlias() {
        val variants = listOf(
            "Sky Uno SD",
            "Sky Uno HD",
            "Sky Uno FHD",
            "Sky Uno 4K HEVC"
        )
        val common = variants.map(::channelNameAliases).reduce(Set<String>::intersect)
        assertTrue("Le varianti Sky Uno devono condividere un alias EPG", "skyuno" in common)
    }

    @Test
    fun multiWordChannelNamesKeepTheirIdentity() {
        val variants = listOf(
            "Sky Cinema Collection SD",
            "Sky Cinema Collection Full HD",
            "IT: Sky Cinema Collection H265"
        )
        val common = variants.map(::channelNameAliases).reduce(Set<String>::intersect)
        assertTrue("Il nome completo deve essere usato per associare la guida", "skycinemacollection" in common)
    }
}
