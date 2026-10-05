package com.zynelabs.stb

/** A single TV channel from the Stalker portal. */
data class Channel(
    val id: String,
    val number: String,
    val name: String,
    val cmd: String,
    val logo: String = "",
    val genreId: String = ""
)
