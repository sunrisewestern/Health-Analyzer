package com.healthanalyzer.app.samsung

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.healthanalyzer.app.data.SamsungExportFiles
import com.healthanalyzer.app.data.SamsungExportImporter
import java.io.InputStream

/**
 * A Samsung Health export folder picked with the system folder picker (Storage Access Framework).
 * Accepts the `samsunghealth_<user>_<timestamp>` folder itself, or a parent folder such as
 * `Samsung Health` that holds one or more exports (the newest is used). Only the exercise CSV and
 * the `jsons/com.samsung.shealth.exercise` subfolders are listed, so large exports stay quick.
 */
class SafSamsungExport(private val resolver: ContentResolver, private val tree: Uri) : SamsungExportFiles {

    private data class Doc(val id: String, val name: String, val isDir: Boolean)

    private val exportDir: Doc
    private val csvDoc: Doc?
    private val jsons: Map<String, String>

    init {
        val root = Doc(DocumentsContract.getTreeDocumentId(tree), "", true)
        val rootChildren = children(root)
        exportDir = if (rootChildren.any { !it.isDir && SamsungExportImporter.isExerciseCsv(it.name) }) {
            root
        } else {
            rootChildren.filter { it.isDir && it.name.startsWith("samsunghealth_") }.maxByOrNull { it.name }
                ?: throw IllegalArgumentException("This folder isn't a Samsung Health export – pick the samsunghealth_… folder")
        }
        val top = if (exportDir == root) rootChildren else children(exportDir)
        csvDoc = top.filter { !it.isDir && SamsungExportImporter.isExerciseCsv(it.name) }.maxByOrNull { it.name }
        val exerciseDir = top.firstOrNull { it.isDir && it.name == "jsons" }
            ?.let { j -> children(j).firstOrNull { it.isDir && it.name == SamsungExportImporter.EXERCISE_TABLE } }
        jsons = buildMap {
            exerciseDir?.let(::children)?.forEach { shard ->
                val files = if (shard.isDir) children(shard) else listOf(shard)
                files.filter { !it.isDir }.forEach { put(it.name, it.id) }
            }
        }
    }

    override fun openExerciseCsv(): InputStream? = csvDoc?.let { open(it.id) }

    override fun openExerciseJson(fileName: String): InputStream? = jsons[fileName]?.let(::open)

    private fun open(docId: String): InputStream? =
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, docId))

    private fun children(dir: Doc): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir.id)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return resolver.query(uri, cols, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        }.orEmpty()
    }
}
