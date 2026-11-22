package org.escc.ecomini

import java.io.File
import java.sql.DriverManager

object TestFiles {
    val projectDir = File(System.getProperty("eco.projectDir") ?: ".")
    val sampleIndex = File(projectDir, "src/test/resources/sample-index.sqlite")
    val embedder = File(projectDir, "src/main/assets/bge-small-en-v1.5-q8_0.gguf")
    val oceanQuery: FloatArray by lazy {
        File(projectDir, "src/test/resources/query-ocean-acidification.txt").readText().trim()
            .split(Regex("\\s+")).map { it.toFloat() }.toFloatArray()
    }

    data class Row(val id: Long, val title: String, val emb: ByteArray, val scale: Float)

    fun rows(db: File = sampleIndex): List<Row> = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
        c.createStatement().executeQuery("SELECT id, title, emb, scale FROM chunks ORDER BY id").use { rs ->
            buildList { while (rs.next()) add(Row(rs.getLong(1), rs.getString(2), rs.getBytes(3), rs.getFloat(4))) }
        }
    }

    fun meta(db: File = sampleIndex): Map<String, String> = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
        c.createStatement().executeQuery("SELECT key, value FROM meta").use { rs ->
            buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
        }
    }

    fun columns(db: File = sampleIndex): Set<String> = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
        c.createStatement().executeQuery("PRAGMA table_info(chunks)").use { rs ->
            buildSet { while (rs.next()) add(rs.getString("name")) }
        }
    }
}
