package io.github.nchalexz.aaperfmon.bridge

import io.github.nchalexz.aaperfmon.cockpit.UiLanguage
import io.github.nchalexz.aaperfmon.telemetry.ConnectionKind
import io.github.nchalexz.aaperfmon.telemetry.ReadingState
import io.github.nchalexz.aaperfmon.telemetry.SignalCatalog
import io.github.nchalexz.aaperfmon.telemetry.SignalValue
import io.github.nchalexz.aaperfmon.telemetry.TelemetryState
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetrySnapshotTest {

    private val now = 1_000_000L

    private fun state(
        connection: ConnectionKind = ConnectionKind.CONNECTED,
        values: Map<String, SignalValue> = emptyMap(),
        lastUpdateMs: Long = now,
    ) = TelemetryState(
        connection = connection,
        connectionLabel = if (connection == ConnectionKind.DEMO) "ДЕМО" else "Wi-Fi ELM327",
        message = if (connection == ConnectionKind.CONNECTED) "v1.5 · 14.2 V" else "",
        values = values,
        lastUpdateMs = lastUpdateMs,
    )

    private fun value(id: String, number: Double, whenMs: Long = now, state: ReadingState = ReadingState.OK) =
        id to SignalValue(number, whenMs, state, "demo")

    @Test
    fun `connected snapshot carries display units and status`() {
        val snapshot = TelemetrySnapshot.build(
            state(
                values = mapOf(
                    value(SignalCatalog.Ids.COOLANT, 86.0),
                    value(SignalCatalog.Ids.BOOST, 62.0),
                    value(SignalCatalog.Ids.OIL_TEMP, 95.0),
                    value(SignalCatalog.Ids.SPEED, 96.0),
                    value(SignalCatalog.Ids.RPM, 1840.0),
                    value(SignalCatalog.Ids.GEAR, 5.0),
                    value(SignalCatalog.Ids.FUEL_L100, 5.0),
                    value(SignalCatalog.Ids.FUEL_LEVEL, 58.0),
                ),
            ),
            now,
        )

        assertEquals(BRIDGE_VERSION, 1)
        assertEquals("connected", snapshot.connection)
        assertEquals("Wi-Fi ELM327", snapshot.source)
        assertEquals(now, snapshot.updatedAt)
        assertTrue(snapshot.status.contains("Wi-Fi ELM327"))
        assertTrue(snapshot.status.contains("14.2 V"))
        assertTrue(snapshot.signals.size <= BRIDGE_MAX_SIGNALS)

        val coolant = requireNotNull(snapshot.signal(SignalCatalog.Ids.COOLANT))
        assertEquals("86", coolant.value)
        assertEquals("°C", coolant.unit)
        assertEquals(86.0, coolant.raw!!, 0.001)
        assertEquals("temperatures", coolant.category)
        assertFalse(coolant.stale)

        val boost = requireNotNull(snapshot.signal(SignalCatalog.Ids.BOOST))
        assertEquals("0,62", boost.value)
        assertEquals("бар", boost.unit)
        assertEquals(0.62, boost.raw!!, 0.001)
        assertEquals("engine", boost.category)

        assertEquals("95", snapshot.signal(SignalCatalog.Ids.OIL_TEMP)!!.value)
        assertEquals("5,0", snapshot.signal(SignalCatalog.Ids.FUEL_L100)!!.value)
    }

    @Test
    fun `missing value is null and stale, never zero`() {
        val snapshot = TelemetrySnapshot.build(state(values = emptyMap()), now)
        val rpm = requireNotNull(snapshot.signal(SignalCatalog.Ids.RPM))
        assertNull(rpm.value)
        assertNull(rpm.raw)
        assertTrue(rpm.stale)
    }

    @Test
    fun `old reading is stale, single failed attempt keeps the number`() {
        val stale = TelemetrySnapshot.build(
            state(values = mapOf(value(SignalCatalog.Ids.COOLANT, 86.0, now - 60_000))),
            now,
        ).signal(SignalCatalog.Ids.COOLANT)!!
        assertTrue(stale.stale)
        assertEquals("86", stale.value)
        assertEquals("stale", stale.status)

        // Одиночная ошибка не стирает последнее корректное значение:
        // значение остаётся действительным, пока не истёк срок 5 секунд.
        val failed = TelemetrySnapshot.build(
            state(
                values = mapOf(
                    value(SignalCatalog.Ids.COOLANT, 86.0, now, ReadingState.ERROR),
                ),
            ),
            now,
        ).signal(SignalCatalog.Ids.COOLANT)!!
        assertEquals("86", failed.value)
        assertEquals(86.0, failed.raw!!, 0.001)
        assertFalse(failed.stale)
        assertEquals("valid", failed.status)
    }

    @Test
    fun `warning follows the cockpit threshold in display units`() {
        val hot = TelemetrySnapshot.build(
            state(values = mapOf(value(SignalCatalog.Ids.COOLANT, 110.0))),
            now,
        ).signal(SignalCatalog.Ids.COOLANT)!!
        assertTrue(hot.warning)

        val normal = TelemetrySnapshot.build(
            state(values = mapOf(value(SignalCatalog.Ids.COOLANT, 86.0))),
            now,
        ).signal(SignalCatalog.Ids.COOLANT)!!
        assertFalse(normal.warning)
    }

    @Test
    fun `demo snapshot is marked demo and has no source`() {
        val snapshot = TelemetrySnapshot.build(
            state(connection = ConnectionKind.DEMO, values = mapOf(value(SignalCatalog.Ids.SPEED, 96.0))),
            now,
        )
        assertEquals("demo", snapshot.connection)
        assertEquals("ДЕМО", snapshot.status)
        assertEquals("", snapshot.source)
        assertEquals("96", snapshot.signal(SignalCatalog.Ids.SPEED)!!.value)
    }

    @Test
    fun `signals json has the contract fields`() {
        val snapshot = TelemetrySnapshot.build(
            state(
                values = mapOf(
                    value(SignalCatalog.Ids.COOLANT, 86.0),
                    value(SignalCatalog.Ids.BOOST, 62.0),
                ),
            ),
            now,
        )
        val array = JSONArray(TelemetrySnapshot.signalsJson(snapshot))
        assertEquals(snapshot.signals.size, array.length())

        var coolant: org.json.JSONObject? = null
        var boost: org.json.JSONObject? = null
        var rpm: org.json.JSONObject? = null
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            when (row.getString("id")) {
                SignalCatalog.Ids.COOLANT -> coolant = row
                SignalCatalog.Ids.BOOST -> boost = row
                SignalCatalog.Ids.RPM -> rpm = row
            }
        }

        assertNotNull(coolant)
        assertEquals("86", coolant!!.getString("value"))
        assertEquals("°C", coolant.getString("unit"))
        assertEquals(86.0, coolant.getDouble("raw"), 0.001)
        assertFalse(coolant.getBoolean("stale"))
        assertEquals("temperatures", coolant.getString("category"))

        assertNotNull(boost)
        assertEquals("0,62", boost!!.getString("value"))
        assertEquals("бар", boost.getString("unit"))
        assertEquals(0.62, boost.getDouble("raw"), 0.001)

        assertNotNull(rpm)
        assertTrue(rpm!!.isNull("value"))
        assertTrue(rpm.isNull("raw"))
        assertTrue(rpm.getBoolean("stale"))
    }


    @Test
    fun `a signal appears once per display category`() {
        val snapshot = TelemetrySnapshot.build(state(), now)
        val boostCategories = snapshot.signals
            .filter { it.id == SignalCatalog.Ids.BOOST }
            .map { it.category }
        assertTrue(boostCategories.contains("engine"))
        assertTrue(boostCategories.contains("turbo"))

        val keys = snapshot.signals.map { it.id to it.category }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `demo values are generated without a running service`() {
        val snapshot = TelemetrySnapshot.build(
            state(connection = ConnectionKind.DEMO, values = emptyMap()),
            now,
        )
        val coolant = requireNotNull(snapshot.signal(SignalCatalog.Ids.COOLANT))
        val boost = requireNotNull(snapshot.signal(SignalCatalog.Ids.BOOST))
        assertEquals("86", coolant.value)
        assertEquals("0,62", boost.value)
        assertFalse(coolant.stale)
        assertEquals("demo", snapshot.connection)
    }

    @Test
    fun `all main cockpit signals are present`() {
        val snapshot = TelemetrySnapshot.build(state(), now)
        listOf(
            SignalCatalog.Ids.COOLANT,
            SignalCatalog.Ids.BOOST,
            SignalCatalog.Ids.OIL_TEMP,
            SignalCatalog.Ids.SPEED,
            SignalCatalog.Ids.RPM,
            SignalCatalog.Ids.GEAR,
            SignalCatalog.Ids.FUEL_L100,
            SignalCatalog.Ids.FUEL_LEVEL,
        ).forEach { assertNotNull("missing $it", snapshot.signal(it)) }
    }

    @Test
    fun `labels and units follow the cockpit language`() {
        val values = mapOf(
            value(SignalCatalog.Ids.COOLANT, 86.0),
            value(SignalCatalog.Ids.BOOST, 62.0),
        )
        val en = TelemetrySnapshot.build(state(values = values), now, UiLanguage.EN)
        assertEquals("Coolant", en.signal(SignalCatalog.Ids.COOLANT)!!.label)
        assertEquals("bar", en.signal(SignalCatalog.Ids.BOOST)!!.unit)

        val ru = TelemetrySnapshot.build(state(values = values), now, UiLanguage.RU)
        assertEquals("ОЖ", ru.signal(SignalCatalog.Ids.COOLANT)!!.label)
        assertEquals("бар", ru.signal(SignalCatalog.Ids.BOOST)!!.unit)
    }

    @Test
    fun `updatedAt is the newest sent value, not the process clock`() {
        val newer = now + 5_000
        val snapshot = TelemetrySnapshot.build(
            state(
                values = mapOf(
                    value(SignalCatalog.Ids.COOLANT, 86.0, now - 2_000),
                    value(SignalCatalog.Ids.RPM, 1840.0, newer),
                ),
                lastUpdateMs = now + 60_000,
            ),
            now + 90_000,
        )
        assertEquals(newer, snapshot.updatedAt)

        val empty = TelemetrySnapshot.build(state(values = emptyMap()), now)
        assertEquals(0L, empty.updatedAt)
    }
}
