package org.bogsnebes.engines

fun TelegramChat.isGroupLike(): Boolean = type == "group" || type == "supergroup"

fun TelegramUser.toDisplayName(): String {
    val fullName = listOfNotNull(
        firstName.trim().takeIf { it.isNotEmpty() },
        lastName?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(" ")

    return when {
        fullName.isNotBlank() -> fullName
        !username.isNullOrBlank() -> "@$username"
        else -> id.toString()
    }
}
