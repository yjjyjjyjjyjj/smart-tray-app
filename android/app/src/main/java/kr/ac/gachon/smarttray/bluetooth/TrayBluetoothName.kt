package kr.ac.gachon.smarttray.bluetooth

/** An explicit discovery/name-change value takes precedence over Android's cached name. */
internal fun trayBluetoothName(discoveredName: String?, cachedName: String?): String? =
    (discoveredName ?: cachedName)?.trim()?.takeIf { it == "HC-06" || it == "AMATTA" }
