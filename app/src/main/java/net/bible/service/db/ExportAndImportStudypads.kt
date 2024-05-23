/*
 * Copyright (c) 2024 Martin Denham, Tuomas Airaksinen and the AndBible contributors.
 *
 * This file is part of AndBible: Bible Study (http://github.com/AndBible/and-bible).
 *
 * AndBible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * AndBible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with AndBible.
 * If not, see http://www.gnu.org/licenses/.
 */

package net.bible.service.db

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.bible.android.BibleApplication
import net.bible.android.activity.BuildConfig
import net.bible.android.activity.R
import net.bible.android.control.backup.BackupControl
import net.bible.android.control.backup.DATABASE_BACKUP_NAME
import net.bible.android.control.backup.ZIP_MIMETYPE
import net.bible.android.database.BookmarkDatabase
import net.bible.android.database.bookmarks.BookmarkEntities
import net.bible.android.database.migrations.getColumnNames
import net.bible.android.database.migrations.getColumnNamesJoined
import net.bible.android.database.migrations.joinColumnNames
import net.bible.android.view.activity.base.ActivityBase
import net.bible.service.common.CommonUtils
import net.bible.service.common.CommonUtils.grantUriReadPermissions
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "ExportStudyPad"

private fun copyStudyPad(
    label: BookmarkEntities.Label,
    db: SupportSQLiteDatabase,
    sourceSchema: String = "main",
    targetSchema: String = "export"
) = db.run {
    val labelCols = getColumnNamesJoined(db, "Label", targetSchema)
    val bibleBookmarkCols = getColumnNamesJoined(db, "BibleBookmark", targetSchema)
    val bibleBookmarkToLabelCols = getColumnNamesJoined(db, "BibleBookmarkToLabel", targetSchema)
    val bibleBookmarkNotesCols = getColumnNames(db, "BibleBookmarkNotes", targetSchema)

    val genericBookmarkCols = getColumnNamesJoined(db, "GenericBookmark", targetSchema)
    val genericBookmarkToLabelCols = getColumnNamesJoined(db, "GenericBookmarkToLabel", targetSchema)
    val genericBookmarkNotesCols = getColumnNames(db, "GenericBookmarkNotes", targetSchema)

    val studyPadTextEntryCols = getColumnNamesJoined(db, "StudyPadTextEntry", targetSchema)
    val studyPadTextEntryTextCols = getColumnNamesJoined(db, "StudyPadTextEntryText", targetSchema)

    val labelIdHex = label.id.toString().replace("-", "")
    execSQL("""
            INSERT OR IGNORE INTO $targetSchema.Label ($labelCols) 
            SELECT $labelCols FROM $sourceSchema.Label WHERE id = x'$labelIdHex' 
            """.trimIndent())
    execSQL("""
            INSERT OR IGNORE INTO $targetSchema.BibleBookmark ($bibleBookmarkCols) 
            SELECT $bibleBookmarkCols FROM $sourceSchema.BibleBookmark bb 
            INNER JOIN $sourceSchema.BibleBookmarkToLabel bbl ON bb.id = bbl.bookmarkId 
            WHERE bbl.labelId = x'$labelIdHex' 
            """.trimIndent())
    execSQL("""
            INSERT OR IGNORE INTO $targetSchema.BibleBookmarkNotes (${joinColumnNames(bibleBookmarkNotesCols)}) 
            SELECT ${joinColumnNames(bibleBookmarkNotesCols, "bb")} FROM $sourceSchema.BibleBookmarkNotes bb 
            INNER JOIN $sourceSchema.BibleBookmarkToLabel bbl ON bb.bookmarkId = bbl.bookmarkId 
            WHERE bbl.labelId = x'$labelIdHex' 
            """.trimIndent())
    execSQL("""
            INSERT OR IGNORE INTO $targetSchema.BibleBookmarkToLabel ($bibleBookmarkToLabelCols) 
            SELECT $bibleBookmarkToLabelCols FROM $sourceSchema.BibleBookmarkToLabel 
            WHERE labelId = x'$labelIdHex' 
            """.trimIndent())

    execSQL("""
            INSERT OR IGNORE INTO $targetSchema.GenericBookmark ($genericBookmarkCols) 
            SELECT $genericBookmarkCols FROM $sourceSchema.GenericBookmark bb 
            INNER JOIN $sourceSchema.GenericBookmarkToLabel bbl ON bb.id = bbl.bookmarkId 
            WHERE bbl.labelId = x'$labelIdHex' 
            """.trimIndent())
    execSQL("""
            INSERT OR IGNORE INTO $targetSchema.GenericBookmarkNotes (${joinColumnNames(genericBookmarkNotesCols)}) 
            SELECT ${joinColumnNames(genericBookmarkNotesCols, "bb")} FROM $sourceSchema.GenericBookmarkNotes bb 
            INNER JOIN $sourceSchema.GenericBookmarkToLabel bbl ON bb.bookmarkId = bbl.bookmarkId 
            WHERE bbl.labelId = x'$labelIdHex' 
            """.trimIndent())
    execSQL("""
            INSERT OR IGNORE INTO $targetSchema.GenericBookmarkToLabel ($genericBookmarkToLabelCols) 
            SELECT $genericBookmarkToLabelCols FROM $sourceSchema.GenericBookmarkToLabel 
            WHERE labelId = x'$labelIdHex' 
            """.trimIndent())

    execSQL("""
            INSERT OR IGNORE INTO $targetSchema.StudyPadTextEntry ($studyPadTextEntryCols) 
            SELECT $studyPadTextEntryCols FROM $sourceSchema.StudyPadTextEntry te  
            WHERE te.labelId = x'$labelIdHex' 
            """.trimIndent())
    execSQL("""
            INSERT OR IGNORE INTO $targetSchema.StudyPadTextEntryText ($studyPadTextEntryTextCols) 
            SELECT $studyPadTextEntryTextCols FROM $sourceSchema.StudyPadTextEntryText tet 
            INNER JOIN $sourceSchema.StudyPadTextEntry te ON tet.studyPadTextEntryId = te.id 
            WHERE te.labelId = x'$labelIdHex' 
            """.trimIndent())
}

suspend fun exportStudyPads(labels: List<BookmarkEntities.Label>, activity: ActivityBase) = withContext(Dispatchers.IO) {
    val exportDbFile = CommonUtils.tmpFile
    val exportDb = DatabaseContainer.instance.getBookmarkDb(exportDbFile.absolutePath)
    exportDb.openHelper.writableDatabase.use {}
    DatabaseContainer.instance.bookmarkDb.openHelper.writableDatabase.run {
        execSQL("ATTACH DATABASE '${exportDbFile.absolutePath}' AS export")
        beginTransaction()
        for (label in labels) {
            copyStudyPad(label, this)
        }
        setTransactionSuccessful()
        endTransaction()
        execSQL("DETACH DATABASE export")
    }

    val filename = if (labels.size > 1) "StudyPads.abdb" else labels.first().name + ".adbd"
    val zipFile = File(BackupControl.internalDbBackupDir, filename)
    ZipOutputStream(FileOutputStream(zipFile)).use { outFile ->
        FileInputStream(exportDbFile).use { inFile ->
            BufferedInputStream(inFile).use { origin ->
                val entry = ZipEntry("db/${BookmarkDatabase.dbFileName}")
                outFile.putNextEntry(entry)
                origin.copyTo(outFile)
            }
        }
    }
    exportDbFile.delete()
    sendFile(filename, zipFile, activity)
    zipFile.delete()
}

suspend fun sendFile(filename: String, file: File, activity: ActivityBase) {
    val subject = activity.getString(R.string.exported_studypads_subject)
    val message = activity.getString(R.string.exported_studypads_message, CommonUtils.applicationNameMedium)
    val uri = FileProvider.getUriForFile(activity, BuildConfig.APPLICATION_ID + ".provider", file)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, message)
        type = ZIP_MIMETYPE
    }
    val saveIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = ZIP_MIMETYPE
        putExtra(Intent.EXTRA_TITLE, filename)
    }

    val chooserIntent = Intent.createChooser(shareIntent, activity.getString(R.string.send_backup_file))
    chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(saveIntent))
    grantUriReadPermissions(chooserIntent, uri)
    activity.awaitIntent(chooserIntent).data?.data?.let {
        val out = BibleApplication.application.contentResolver.openOutputStream(it)!!
        FileInputStream(file).copyTo(out)
    }
}
