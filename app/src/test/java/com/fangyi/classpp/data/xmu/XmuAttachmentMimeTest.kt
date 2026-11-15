package com.fangyi.classpp.data.xmu

import org.junit.Assert.assertEquals
import org.junit.Test

class XmuAttachmentMimeTest {

    @Test
    fun `common extensions map to concrete mime types`() {
        assertEquals("application/pdf", XmuAttachmentMime.resolve("作业.PDF", "document"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            XmuAttachmentMime.resolve("Talk1-作业.pptx", "document"),
        )
        assertEquals("image/jpeg", XmuAttachmentMime.resolve("photo.jpeg", "document"))
        assertEquals("image/png", XmuAttachmentMime.resolve("figure.png", "image"))
    }

    @Test
    fun `unknown extension falls back to remote type then octet stream`() {
        assertEquals("image/*", XmuAttachmentMime.resolve("blob.bin", "image"))
        assertEquals("video/*", XmuAttachmentMime.resolve("clip.dat", "video"))
        assertEquals("application/octet-stream", XmuAttachmentMime.resolve("file.bin", "document"))
        assertEquals("application/octet-stream", XmuAttachmentMime.resolve("file", ""))
    }
}
