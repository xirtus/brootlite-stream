package app.brootlite.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stream rows must keep seeders, size, codec and indexer visible. */
class StreamRowTest {
    private val archivePack = StreamCandidate(
        addonName = "InternetArchive",
        name = "InternetArchive\n1080p",
        title = "Big Buck Bunny 1080p h265.mp4\n" +
            "Big Buck Bunny 1080p H.265 public domain animation\n" +
            "👤 12 💾 240.5 MB ⚙️ InternetArchive",
        infoHash = "746e56c39fcc76b2fa4a1942624c424db5635359",
        filename = "Big Buck Bunny 1080p h265.mp4",
        seeders = 12
    )

    private val hevc = StreamCandidate(
        addonName = "Torznab",
        name = "Torznab\n4k HDR",
        title = "PublicDomain.Feature.2024.2160p.WEB.HDR.x265-GRP\n👤 312 💾 14.2 GB ⚙️ Torznab",
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        seeders = 312
    )

    @Test fun telemetryShowsSeedsSizeAndProvider() {
        val t = archivePack.telemetryHeader
        assertTrue(t, t.contains("12 Seeds"))
        assertTrue(t, t.contains("240.5 MB"))
        assertTrue(t, t.contains("InternetArchive"))
        assertEquals("InternetArchive", archivePack.extractedIndexer)
    }

    @Test fun telemetryShowsModernCodec() {
        val t = hevc.telemetryHeader
        assertTrue(t, t.contains("312 Seeds"))
        assertTrue(t, t.contains("14.2 GB"))
        assertTrue(t, t.contains("4K"))
        assertTrue(t, t.contains("HEVC/H.265"))
        assertTrue(hevc.isPreferredModernCodec)
        assertEquals("Torznab", hevc.extractedIndexer)
    }

    @Test fun av1IsPreferred() {
        val av1 = hevc.copy(title = "Show.S01E01.1080p.WEB.AV1.Opus-GRP\n👤 9 💾 700 MB ⚙️ EXT")
        assertTrue(av1.extractedCodecTags.toString(), "AV1" in av1.extractedCodecTags)
        assertTrue(av1.isPreferredModernCodec)
    }

    @Test fun detailsKeepEveryTitleLine() {
        val d = archivePack.displayDetails
        assertTrue(d, d.contains("Big Buck Bunny"))
        assertTrue(d, d.contains("InternetArchive"))
    }

    @Test fun defaultsIncludeCinemetaAndOpenSubtitles() {
        val addons = AppPrefs.defaultAddons()
        assertTrue(addons.any { it.id == "com.linvo.cinemeta" })
        assertTrue(addons.any { it.id == "org.stremio.opensubtitlesv3" })
        assertTrue(addons.all { it.id in setOf("com.linvo.cinemeta", "org.stremio.publicdomainmovies", "org.stremio.opensubtitlesv3") || it.transportUrl.startsWith("https://") })
    }
}

