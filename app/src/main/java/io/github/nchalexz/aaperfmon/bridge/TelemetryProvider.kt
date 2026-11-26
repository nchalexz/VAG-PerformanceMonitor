package io.github.nchalexz.aaperfmon.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.github.nchalexz.aaperfmon.cockpit.CockpitPrefs
import io.github.nchalexz.aaperfmon.cockpit.UiLanguage
import io.github.nchalexz.aaperfmon.cockpit.resolveLanguage
import io.github.nchalexz.aaperfmon.telemetry.Telemetry

/**
 * Read-only telemetry bridge for DashGrid.
 *
 * The provider only exposes [Telemetry.state] — the values the app has already
 * collected. It never starts the telemetry service, never connects the adapter
 * and never polls a PID, so a read from another app is not a reason to open OBD.
 *
 * Any app that declares the normal permission can read the current values.
 * There is no history, VIN, adapter path or settings here; write methods are
 * intentionally unsupported.
 */
class TelemetryProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        // The manifest already sets readPermission; enforce again for OEM builds
        // and for callers that reach the provider through a non-standard path.
        // The check comes before the method name so no caller can probe the
        // provider without the permission, whatever method it sends.
        context.enforceCallingOrSelfPermission(BRIDGE_READ_PERMISSION, "READ_TELEMETRY is required")
        if (method != BRIDGE_METHOD_SNAPSHOT) return null
        // Labels and units follow VPM's own cockpit language; DashGrid draws
        // the numbers VPM shows, not a second localization of the same signal.
        val language = runCatching {
            CockpitPrefs.init(context)
            CockpitPrefs.state.value.resolveLanguage()
        }.getOrDefault(UiLanguage.EN)
        val snapshot = TelemetrySnapshot.build(
            Telemetry.state.value,
            System.currentTimeMillis(),
            language,
        )
        return Bundle().apply {
            putInt("version", BRIDGE_VERSION)
            putString("connection", snapshot.connection)
            putString("status", snapshot.status)
            putString("source", snapshot.source)
            putLong("updatedAt", snapshot.updatedAt)
            putString("signals", TelemetrySnapshot.signalsJson(snapshot))
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
