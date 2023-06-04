/*
 * Copyright (c) 2023 Martin Denham, Tuomas Airaksinen and the AndBible contributors.
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

package net.bible.android.database.migrations

val separateText = makeMigration(1..2) { _db ->
    _db.execSQL("CREATE TABLE IF NOT EXISTS `BookmarkNotes` (`bookmarkId` BLOB NOT NULL, `notes` TEXT, PRIMARY KEY(`bookmarkId`), FOREIGN KEY(`bookmarkId`) REFERENCES `Bookmark`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
    _db.execSQL("CREATE TABLE IF NOT EXISTS `StudyPadTextEntryText` (`studyPadTextEntryId` BLOB NOT NULL, `text` TEXT NOT NULL, PRIMARY KEY(`studyPadTextEntryId`), FOREIGN KEY(`studyPadTextEntryId`) REFERENCES `StudyPadTextEntry`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
    _db.execSQL("INSERT INTO BookmarkNotes (bookmarkId, notes) SELECT id, notes FROM Bookmark WHERE notes IS NOT NULL")
    _db.execSQL("INSERT INTO StudyPadTextEntryText (studyPadTextEntryId, text) SELECT id, text FROM StudyPadTextEntry")
    _db.execSQL("ALTER TABLE Bookmark DROP COLUMN notes")
    _db.execSQL("ALTER TABLE StudyPadTextEntry DROP COLUMN text")
    _db.execSQL("CREATE VIEW `BookmarkWithNotes` AS SELECT b.*, bn.notes FROM Bookmark b LEFT OUTER JOIN BookmarkNotes bn ON b.id = bn.bookmarkId");
    _db.execSQL("CREATE VIEW `StudyPadTextEntryWithText` AS SELECT e.*, t.text FROM StudyPadTextEntry e INNER JOIN StudyPadTextEntryText t ON e.id = t.studyPadTextEntryId");
}

val bookmarkMigrations: Array<Migration> = arrayOf(separateText)

const val BOOKMARK_DATABASE_VERSION = 2
