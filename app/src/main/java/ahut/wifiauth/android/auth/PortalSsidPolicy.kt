package ahut.wifiauth.android.auth

/** Exact SSID policy shared by network access and automatic-login target handling. */
object PortalSsidPolicy {
    val WORK_SSIDS: Set<String> = linkedSetOf("AHUT-FREE", "AHUT-wifi6")
    val DEFAULT_TARGET_SSIDS: Set<String> = setOf("AHUT-FREE")

    /** Android wraps some SSIDs in quotes. Remove only that wrapper; preserve all other characters. */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val value = if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            raw.substring(1, raw.length - 1)
        } else raw
        return value.takeUnless { it.isEmpty() || it == "<unknown ssid>" || it == "0x" }
    }

    /** Inputs after Android's read boundary must already be canonical. */
    fun isWorkSsid(canonicalSsid: String?): Boolean = canonicalSsid in WORK_SSIDS

    fun sanitizeTargets(targets: Set<String>): Set<String> =
        targets.filterTo(linkedSetOf()) { it in WORK_SSIDS }
}
