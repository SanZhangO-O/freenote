package com.zqr.freenote

import androidx.navigation.NavType
import androidx.navigation.navArgument

/**
 * Centralized navigation route definitions so raw route strings live in one place.
 */
object Routes {
    const val ARG_NOTE_ID = "noteId"

    const val NOTES = "notes"
    const val EDIT_NEW = "edit"
    const val EDIT = "edit/{" + ARG_NOTE_ID + "}"
    const val LOCK = "lock/{" + ARG_NOTE_ID + "}"
    const val TRASH = "trash"
    const val SETTINGS = "settings"

    fun edit(noteId: String?): String =
        if (noteId == null) EDIT_NEW else "edit/$noteId"

    fun lock(noteId: String): String = "lock/$noteId"

    fun noteIdNavArg() = navArgument(ARG_NOTE_ID) { type = NavType.StringType }
}
