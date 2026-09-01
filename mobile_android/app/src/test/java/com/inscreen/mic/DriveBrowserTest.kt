package com.inscreen.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveBrowserTest {
    @Test fun extractsFolderIdsFromSupportedLinks() {
        assertEquals("1AbCdEfGhIjKlMn", DriveLinkPolicy.folderId("https://drive.google.com/drive/folders/1AbCdEfGhIjKlMn?usp=sharing"))
        assertEquals("1AbCdEfGhIjKlMn", DriveLinkPolicy.folderId("https://drive.google.com/open?id=1AbCdEfGhIjKlMn"))
    }

    @Test fun rejectsNonDriveAndMalformedLinks() {
        assertNull(DriveLinkPolicy.folderId("https://example.com/drive/folders/1AbCdEfGhIjKlMn"))
        assertNull(DriveLinkPolicy.folderId("http://drive.google.com/drive/folders/1AbCdEfGhIjKlMn"))
        assertNull(DriveLinkPolicy.folderId("https://drive.google.com/drive/folders/short"))
    }

    @Test fun foldersSortBeforeFilesIgnoringCase() {
        val values = listOf(
            DriveItem("1", "zeta.pdf", "application/pdf", 4, "", "1"),
            DriveItem("2", "Beta", DriveItem.FOLDER_MIME, null, "", "1"),
            DriveItem("3", "alpha", DriveItem.FOLDER_MIME, null, "", "1"),
            DriveItem("4", "Alpha.pdf", "application/pdf", 4, "", "1"),
        )
        assertEquals(listOf("alpha", "Beta", "Alpha.pdf", "zeta.pdf"), DriveItemPolicy.sorted(values).map { it.name })
    }

    @Test fun mapsGoogleWorkspaceFilesToPortableFormats() {
        val document = DriveItem("1", "Teoría", "application/vnd.google-apps.document", null, "", "1")
        assertEquals("application/pdf" to "Teoría.pdf", DriveItemPolicy.export(document))
    }
    @Test fun folderShortcutsResolveToTheirTarget() {
        val shortcut = DriveItem(
            "shortcut-id", "Semana 3", "application/vnd.google-apps.shortcut", null, "", "1",
            "target-folder-id", DriveItem.FOLDER_MIME,
        )

        assertEquals(true, shortcut.isFolder)
        assertEquals("target-folder-id", shortcut.effectiveId)
    }
}
