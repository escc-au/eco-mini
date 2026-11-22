package org.escc.ecomini

import org.escc.ecomini.files.Checks
import org.escc.ecomini.files.Manifest
import org.escc.ecomini.files.ManifestFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestTest {
    private val sha = "a".repeat(64)
    private fun file(name: String, role: String, size: Long = 10, s: String = sha) =
        """{"name":"$name","role":"$role","size":$size,"sha256":"$s"}"""
    private fun manifest(vararg files: String, version: String = "\"3\"") =
        """{"version":$version,"files":[${files.joinToString(",")}]}"""

    @Test fun parsesValidManifest() {
        val m = Manifest.parse(manifest(file("eco-4b-Q6_K.gguf", "model", 3_300_000_000), file("eco-index.sqlite", "index", 400)))
        assertEquals("3", m.version)
        assertEquals("eco-4b-Q6_K.gguf", m.model.name)
        assertEquals("eco-index.sqlite", m.index.name)
        assertEquals(3_300_000_400, m.totalSize)
    }

    @Test fun numericVersionAccepted() {
        assertEquals("2", Manifest.parse(manifest(file("m.gguf", "model"), file("i.sqlite", "index"), version = "2")).version)
    }

    private fun rejects(json: String) {
        try {
            Manifest.parse(json)
            throw AssertionError("accepted: $json")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun rejectsBadManifests() {
        rejects("not json")
        rejects("""{"files":[${file("m.gguf", "model")},${file("i.sqlite", "index")}]}""")
        rejects(manifest(file("m.gguf", "model")))
        rejects(manifest(file("m.gguf", "model"), file("i.sqlite", "index"), file("x.gguf", "model")))
        rejects(manifest(file("../m.gguf", "model"), file("i.sqlite", "index")))
        rejects(manifest(file("dir/m.gguf", "model"), file("i.sqlite", "index")))
        rejects(manifest(file("m.gguf", "model", s = "abc"), file("i.sqlite", "index")))
        rejects(manifest(file("m.gguf", "model", size = 0), file("i.sqlite", "index")))
        rejects(manifest(file("m.gguf", "weights"), file("i.sqlite", "index")))
        rejects(manifest(file("same", "model"), file("same", "index")))
    }

    @Test fun sha256DetectsBadFile() {
        val f = File.createTempFile("eco", ".bin")
        try {
            f.writeText("hello eco")
            val good = Checks.sha256(f)
            assertEquals(64, good.length)
            val entry = ManifestFile(f.name, "model", f.length(), good)
            assertTrue(Checks.matches(f, entry))
            f.writeText("hello ecO")
            assertFalse("one changed byte", Checks.matches(f, entry))
            f.writeText("hello eco!")
            assertFalse("wrong size", Checks.matches(f, entry))
            assertFalse("missing file", Checks.matches(File(f.path + ".missing"), entry))
        } finally {
            f.delete()
        }
    }

    @Test fun sha256KnownValue() {
        val f = File.createTempFile("eco", ".bin")
        try {
            f.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Checks.sha256(f))
        } finally {
            f.delete()
        }
    }
}
