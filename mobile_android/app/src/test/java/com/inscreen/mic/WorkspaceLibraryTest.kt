package com.inscreen.mic

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class WorkspaceLibraryTest {
    private val workspace = "workspace-1234567890"
    private fun inventory() = JSONObject().put("version", 1).put("workspaceId", workspace).put("revision", "revision-1234567890")
        .put("files", JSONArray().put(JSONObject().put("id", "a".repeat(64)).put("hash", "b".repeat(64)).put("size", 4)))
        .put("items", JSONArray().put(JSONObject().put("fileId", "a".repeat(64))))
    private fun rejected(action: () -> Unit) { try { action(); fail("Debía rechazar la entrada") } catch (_: IllegalArgumentException) { } }
    @Test fun parsesOnlyHttpsWorkspaceLinks() {
        val token = "wl1." + "a".repeat(100)
        val link = WorkspaceLink.parse("inscreen://workspace-pair?base_url=" + URLEncoder.encode("https://cursado.example.com", "UTF-8") + "&token=$token")
        assertEquals("https://cursado.example.com", link.baseUrl)
        assertEquals(token, link.token)
        rejected { WorkspaceLink.parse("inscreen://provider-pair?token=$token") }
        rejected { WorkspaceLink.parse("inscreen://workspace-pair?base_url=http%3A%2F%2Flocalhost&token=$token") }
        assertFalse(WorkspaceLink.validBase("https://user:password@example.com"))
        assertFalse(WorkspaceLink.validBase("https://example.com/other"))
        assertFalse(WorkspaceLink.validBase("https://example.com?key=secret"))
    }
    @Test fun verifiesInventoryScopeAndReferences() {
        WorkspaceLibraryStore.validateManifest(inventory(), workspace)
        rejected { WorkspaceLibraryStore.validateManifest(inventory(), "another-workspace") }
        val badHash = inventory(); badHash.getJSONArray("files").getJSONObject(0).put("hash", "../escape")
        rejected { WorkspaceLibraryStore.validateManifest(badHash, workspace) }
        val badReference = inventory(); badReference.getJSONArray("items").getJSONObject(0).put("fileId", "c".repeat(64))
        rejected { WorkspaceLibraryStore.validateManifest(badReference, workspace) }
        val duplicate = inventory(); duplicate.getJSONArray("files").put(duplicate.getJSONArray("files").getJSONObject(0))
        rejected { WorkspaceLibraryStore.validateManifest(duplicate, workspace) }
        val deleted = inventory().put("files", JSONArray()).put("items", JSONArray())
        WorkspaceLibraryStore.validateManifest(deleted, workspace)
    }
    @Test fun computesIntegrityHashAndRejectsPaths() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", WorkspaceLibraryStore.sha256("abc".toByteArray()))
        assertFalse(WorkspaceLibraryStore.validHash("../private"))
        assertFalse(WorkspaceLibraryStore.validId("../another-space"))
    }
}
