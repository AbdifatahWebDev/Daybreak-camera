package com.brekwol.daybreak.data

/** The file formats Daybreak writes. JPEG never enters the library. */
enum class PhotoFormat(
    val extension: String,
    val mime: String,
    /** Short label for badges. */
    val badge: String,
    /** Name shown in settings and photo info. */
    val label: String,
) {
    Jxl("jxl", "image/jxl", "JXL", "JPEG XL"),
    Heic("heic", "image/heic", "HEIC", "HEIC"),
    Dng("dng", "image/x-adobe-dng", "RAW", "RAW (DNG)"),
    ;

    companion object {
        /** Formats a processed photo can be saved in. */
        val processed = listOf(Jxl, Heic)

        fun fromFileName(name: String): PhotoFormat? =
            entries.firstOrNull { name.endsWith(".${it.extension}", ignoreCase = true) }

        fun fromKey(key: String?): PhotoFormat = processed.firstOrNull { it.extension == key } ?: Jxl
    }
}
