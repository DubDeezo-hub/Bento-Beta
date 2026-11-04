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
        assertEquals(listOf("a.txt", "b.bin"), requests.map { it.name })
        assertEquals(listOf("/data/Mods", "/data/Mods/sub/deep"), requests.map { it.dir })
        assertEquals(listOf("content://x/1", "content://x/2"), requests.map { it.fileUri })
    }
}
