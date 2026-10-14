package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DashAudioManifestParserTest {
    @Test
    fun parsesAudioSegmentTemplateAndExpandsNumberTimeAndRepresentationId() {
        val manifest = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011"
                 type="static" mediaPresentationDuration="PT8S">
              <BaseURL>https://cdn.example.test/film/</BaseURL>
              <Period duration="PT8S">
                <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
                  <SegmentTemplate timescale="1" startNumber="5"
                    initialization="init-${'$'}RepresentationID${'$'}.m4s"
                    media="chunk-${'$'}Number%03d${'$'}-${'$'}Time${'$'}.m4s">
                    <SegmentTimeline>
                      <S t="0" d="4" r="1" />
                    </SegmentTimeline>
                  </SegmentTemplate>
                  <Representation id="aud1" bandwidth="128000" mimeType="audio/mp4" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val parsed = DashAudioManifestParser.parse(
            "https://cdn.example.test/film/manifest.mpd",
            manifest,
        )

        assertEquals("https://cdn.example.test/film/init-aud1.m4s", parsed.initializationUrl)
        assertEquals(2, parsed.segments.size)
        assertEquals("https://cdn.example.test/film/chunk-005-0.m4s", parsed.segments[0].url)
        assertEquals("https://cdn.example.test/film/chunk-006-4.m4s", parsed.segments[1].url)
        assertEquals(4_000L, parsed.segments[0].durationMs)
        assertEquals(8_000L, parsed.totalDurationMs)
        assertEquals(null, parsed.directAudioUrl)
    }

    @Test
    fun parsesDurationBasedTemplateAndSegmentList() {
        val templateManifest = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT8S">
                <AdaptationSet mimeType="audio/mp4">
                  <SegmentTemplate timescale="1000" duration="4000"
                    initialization="init.mp4" media="seg-${'$'}Number${'$'}.m4s" />
                  <Representation id="audio" bandwidth="96000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val template = DashAudioManifestParser.parse(
            "https://cdn.example.test/path/manifest.mpd",
            templateManifest,
        )
        assertEquals(2, template.segments.size)
        assertEquals("https://cdn.example.test/path/seg-1.m4s", template.segments[0].url)
        assertEquals("https://cdn.example.test/path/seg-2.m4s", template.segments[1].url)

        val listManifest = """
            <MPD type="static" mediaPresentationDuration="PT6S">
              <BaseURL>https://cdn.example.test/a/</BaseURL>
              <Period duration="PT6S">
                <AdaptationSet contentType="audio">
                  <Representation id="a" bandwidth="128000">
                    <SegmentList>
                      <Initialization sourceURL="init.mp4" />
                      <SegmentURL media="one.m4s" />
                      <SegmentURL media="two.m4s" />
                    </SegmentList>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val list = DashAudioManifestParser.parse(
            "https://cdn.example.test/a/manifest.mpd",
            listManifest,
        )
        assertEquals("https://cdn.example.test/a/init.mp4", list.initializationUrl)
        assertEquals(2, list.segments.size)
        assertEquals("https://cdn.example.test/a/two.m4s", list.segments[1].url)
    }

    @Test
    fun picksAudioRepresentationAndRefusesDrmDynamicAndUnsafeLayouts() {
        val multiRepresentation = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S">
                <AdaptationSet>
                  <SegmentTemplate duration="4" media="a-${'
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD xmlns:cenc="urn:mpeg:cenc:2013" type="static" mediaPresentationDuration="PT4S">
              <cenc:ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesAudioRepresentationWithoutSegmentsOrExplicitMediaBaseUrl() {
        val manifest = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S">
                <AdaptationSet contentType="audio" mimeType="audio/mp4">
                  <Representation id="audio" bandwidth="96000" mimeType="audio/mp4" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/manifest.mpd", manifest)
        }
    }

    @Test
    fun refusesSegmentNumberAndTimelineOverflow() {
        val numberOverflow = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT8S">
                <AdaptationSet contentType="audio" mimeType="audio/mp4">
                  <SegmentTemplate timescale="1" duration="4"
                    startNumber="9223372036854775807"
                    initialization="init.mp4" media="seg-${'
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}Number${'
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}.m4s" initialization="init.mp4" />
                  <Representation id="low" bandwidth="64000" mimeType="audio/mp4" />
                  <Representation id="high" bandwidth="256000" mimeType="audio/mp4" />
                </AdaptationSet>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}Number${'
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}Number${'
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}.m4s" initialization="init.mp4" />
                  <Representation id="low" bandwidth="64000" mimeType="audio/mp4" />
                  <Representation id="high" bandwidth="256000" mimeType="audio/mp4" />
                </AdaptationSet>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}.m4s" />
                  <Representation id="audio" bandwidth="96000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val timelineOverflow = """
            <MPD type="static" mediaPresentationDuration="PT2S">
              <Period duration="PT2S">
                <AdaptationSet contentType="audio" mimeType="audio/mp4">
                  <SegmentTemplate timescale="1"
                    initialization="init.mp4" media="seg-${'
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}Number${'
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}.m4s" initialization="init.mp4" />
                  <Representation id="low" bandwidth="64000" mimeType="audio/mp4" />
                  <Representation id="high" bandwidth="256000" mimeType="audio/mp4" />
                </AdaptationSet>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}Number${'
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}Number${'
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}.m4s" initialization="init.mp4" />
                  <Representation id="low" bandwidth="64000" mimeType="audio/mp4" />
                  <Representation id="high" bandwidth="256000" mimeType="audio/mp4" />
                </AdaptationSet>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}-${'
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}Number${'
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}.m4s" initialization="init.mp4" />
                  <Representation id="low" bandwidth="64000" mimeType="audio/mp4" />
                  <Representation id="high" bandwidth="256000" mimeType="audio/mp4" />
                </AdaptationSet>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}Time${'
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}Number${'
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}.m4s" initialization="init.mp4" />
                  <Representation id="low" bandwidth="64000" mimeType="audio/mp4" />
                  <Representation id="high" bandwidth="256000" mimeType="audio/mp4" />
                </AdaptationSet>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}.m4s">
                    <SegmentTimeline>
                      <S t="9223372036854775807" d="1" r="1" />
                    </SegmentTimeline>
                  </SegmentTemplate>
                  <Representation id="audio" bandwidth="96000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/n.mpd", numberOverflow)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/t.mpd", timelineOverflow)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}Number${'
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
}.m4s" initialization="init.mp4" />
                  <Representation id="low" bandwidth="64000" mimeType="audio/mp4" />
                  <Representation id="high" bandwidth="256000" mimeType="audio/mp4" />
                </AdaptationSet>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selected = DashAudioManifestParser.parse(
            "https://cdn.example.test/manifest.mpd",
            multiRepresentation,
        )
        assertEquals("https://cdn.example.test/a-1.m4s", selected.segments.first().url)

        val encrypted = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" />
              <Period duration="PT4S"><AdaptationSet contentType="audio" /></Period>
            </MPD>
        """.trimIndent()
        val dynamic = """
            <MPD type="dynamic"><Period><AdaptationSet contentType="audio" /></Period></MPD>
        """.trimIndent()
        val multiPeriod = """
            <MPD type="static" mediaPresentationDuration="PT8S">
              <Period duration="PT4S"/><Period duration="PT4S"/>
            </MPD>
        """.trimIndent()
        val segmentBase = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <Representation id="a"><SegmentBase indexRange="0-100"/></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val negativeRepeat = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S"><AdaptationSet contentType="audio">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4">
                  <SegmentTimeline><S t="0" d="4" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="a" bandwidth="96000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", dynamic)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", multiPeriod)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", segmentBase)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            DashAudioManifestParser.parse("https://cdn.example.test/m.mpd", negativeRepeat)
        }
    }

    @Test
    fun refusesOversizedAndUnsafeManifestUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "file:///sdcard/manifest.mpd",
                "<MPD/>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashAudioManifestParser.parse(
                "https://cdn.example.test/manifest.mpd",
                "<MPD>" + " ".repeat(DashAudioManifestParser.MAX_MANIFEST_CHARS + 1) + "</MPD>",
            )
        }
        assertTrue(DashAudioManifestParser.MAX_SEGMENTS > 0)
    }
}
