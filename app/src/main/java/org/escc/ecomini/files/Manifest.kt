package org.escc.ecomini.files

import org.json.JSONObject

data class ManifestFile(val name: String, val role: String, val size: Long, val sha256: String)

data class Manifest(val version: String, val files: List<ManifestFile>) {
    val model: ManifestFile get() = files.first { it.role == ROLE_MODEL }
    val index: ManifestFile get() = files.first { it.role == ROLE_INDEX }
    val totalSize: Long get() = files.sumOf { it.size }

    companion object {
        const val ROLE_MODEL = "model"
        const val ROLE_INDEX = "index"
        private val SHA = Regex("^[0-9a-f]{64}$")
        private val NAME = Regex("^[A-Za-z0-9._-]+$")

        /** Parses manifest.json. Throws IllegalArgumentException with a readable message if it is wrong. */
        fun parse(json: String): Manifest {
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                throw IllegalArgumentException("manifest.json is not valid JSON")
            }
            val version = root.opt("version")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                ?: throw IllegalArgumentException("manifest.json has no version")
            val arr = root.optJSONArray("files") ?: throw IllegalArgumentException("manifest.json has no files list")
            val files = (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("file entry $i is not an object")
                val name = o.optString("name")
                val role = o.optString("role")
                val size = o.optLong("size", -1)
                val sha = o.optString("sha256").lowercase()
                require(NAME.matches(name) && name != "." && name != "..") { "bad file name '$name'" }
                require(role == ROLE_MODEL || role == ROLE_INDEX) { "bad role '$role' for $name" }
                require(size > 0) { "bad size for $name" }
                require(SHA.matches(sha)) { "bad sha256 for $name" }
                ManifestFile(name, role, size, sha)
            }
            require(files.count { it.role == ROLE_MODEL } == 1) { "manifest must list exactly one model" }
            require(files.count { it.role == ROLE_INDEX } == 1) { "manifest must list exactly one index" }
            require(files.map { it.name }.toSet().size == files.size) { "duplicate file names" }
            return Manifest(version, files)
        }
    }
}
