package com.vidultra.pro.utils

enum class LogProfile(val displayName: String, val description: String) {
    REC709("Rec.709", "Standard gamma / BT.709"),
    SLOG("S-Log", "Sony S-Log flat gamma"),
    SLOG2("S-Log2", "Sony S-Log2"),
    SLOG3("S-Log3", "Sony S-Log3"),
    VLOG("V-Log", "Panasonic V-Log"),
    LOGC("LogC", "ARRI Log C"),
    CINEON("Cineon", "Kodak Cineon log"),
    ACES("ACEScc", "Academy Color Encoding System"),
    PQ("PQ", "SMPTE ST 2084 (HDR)"),
    HLG("HLG", "Hybrid Log-Gamma (HDR)"),
    FLAT("Flat", "Generic flat desaturated profile");

    companion object {
        fun fromDisplayName(name: String): LogProfile {
            return entries.find { it.displayName.equals(name, ignoreCase = true) } ?: REC709
        }
    }
}
