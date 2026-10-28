package app.buddyverse.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaRefsTest {
    @Test fun windowsLine() {
        val s = splitMedia("Here's the build:\nMEDIA:C:\\Users\\alex\\buddyverse\\Buddyverse.apk\nEnjoy.")
        assertEquals(listOf("C:\\Users\\alex\\buddyverse\\Buddyverse.apk"), s.paths)
        assertEquals("Here's the build:\nEnjoy.", s.text)
    }

    @Test fun inlineEmphasisSpacesAndDedupe() {
        val s = splitMedia("Done: **MEDIA:/home/a/My Report.pdf** (2 MB). Also MEDIA:/tmp/x.tar.gz\nMEDIA:/tmp/x.tar.gz")
        assertEquals(listOf("/home/a/My Report.pdf", "/tmp/x.tar.gz"), s.paths)
        assertEquals("Done:  (2 MB). Also", s.text)
    }

    @Test fun quotedAndFencedAndPlain() {
        assertEquals(listOf("C:/a b/c.png"), splitMedia("MEDIA:\"C:/a b/c.png\"").paths)
        val fenced = "```\nMEDIA:/x.png\n```"
        assertEquals(MediaSplit(fenced, emptyList()), splitMedia(fenced))
        assertEquals(MediaSplit("no files", emptyList()), splitMedia("no files"))
        assertEquals(emptyList<String>(), splitMedia("MEDIA:/no/extension").paths)
    }

    @Test fun names() {
        assertEquals("Buddyverse.apk", fileNameOf("C:\\Users\\alex\\Buddyverse.apk"))
        assertEquals("x.png", fileNameOf("/tmp/x.png"))
    }
}
