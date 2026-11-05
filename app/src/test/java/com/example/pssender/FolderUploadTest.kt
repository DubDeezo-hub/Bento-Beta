package com.example.pssender

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderUploadTest {
    @Test
    fun aFolderGoesInsideTheUploadFolderUnderItsOwnName() {
        assertEquals("/data/Mods", folderUploadRoot("/data", "Mods"))
        assertEquals("/data/Mods", folderUploadRoot(" /data/ ", "Mods"))
        assertEquals("/data/Folder", folderUploadRoot("", " "))
    }

    @Test
    fun everyFileKeepsItsPlaceInsideTheFolder() {
        val s = FtpSettings("10.0.0.5", 2121, "anonymous", "")
        val files = listOf(PhoneFile("a.txt", "content://x/1", 1L), PhoneFile("sub/deep/b.bin", "content://x/2", 2L))
        val requests = uploadRequests(s, "/data/Mods", files)
        // Biggest first, so the files going side by side finish together
        assertEquals(listOf("b.bin", "a.txt"), requests.map { it.name })
        assertEquals(listOf("/data/Mods/sub/deep", "/data/Mods"), requests.map { it.dir })
        assertEquals(listOf("content://x/2", "content://x/1"), requests.map { it.fileUri })
    }
}
