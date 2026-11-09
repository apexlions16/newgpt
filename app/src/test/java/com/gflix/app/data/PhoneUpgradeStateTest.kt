package com.gflix.app.data

import com.gflix.app.admin.AdminState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PhoneUpgradeStateTest {
    @Test fun preProgressSettingsAndPendingFilesSurviveReadingAndWriting() {
        val previous = """{
          "tmdbKey":"saved-tmdb", "iaAccess":"encrypted-access", "iaSecret":"encrypted-secret",
          "iaPrefix":"saved-prefix", "archiveRightsConfirmed":true,
          "tasks":[{"id":"pending-27","tag":"phone-archive","title":"Saved title","kind":"movie",
            "createdAt":27,"manifest":{"kind":"movie","title":"Saved title","fileName":"film.mkv","size":500,"parts":["film.mkv"]},
            "files":[{"uri":"file:///private/video.mkv","assetName":"film.mkv","offset":0,"length":500,"md5":"saved-md5"}],
            "status":"processing","processingSince":1234}]
        }"""
        val read = AppJson.decodeFromString<AdminState>(previous)
        val saved = AppJson.decodeFromString<AdminState>(AppJson.encodeToString(read.copy(tasks = read.tasks.map {
            it.copy(phase = "verify", percent = -1)
        })))
        assertEquals("saved-tmdb", saved.tmdbKey)
        assertEquals("encrypted-access", saved.iaAccess)
        assertEquals("encrypted-secret", saved.iaSecret)
        assertEquals("saved-prefix", saved.iaPrefix)
        assertTrue(saved.archiveRightsConfirmed)
        assertEquals("pending-27", saved.tasks.single().id)
        assertEquals("saved-md5", saved.tasks.single().files.single().md5)
        assertEquals("file:///private/video.mkv", saved.tasks.single().files.single().uri)
        assertEquals(1234L, saved.tasks.single().processingSince)
        assertEquals("verify", saved.tasks.single().phase)
    }
}
