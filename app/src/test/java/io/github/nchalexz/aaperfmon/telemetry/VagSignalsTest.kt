package io.github.nchalexz.aaperfmon.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VagSignalsTest {

    @Test
    fun `catalog contains the curated MQB TDI set`() {
        assertEquals(83, VagSignals.all.size)
        assertEquals(83, VagSignals.all.map { it.id }.distinct().size)
    }

    @Test
    fun `every VAG signal has an address, DID and decoder`() {
        for (definition in VagSignals.all) {
            assertTrue("${definition.id}: не UDS", definition.isUds)
            assertNotNull("${definition.id}: нет txId", definition.txId)
            assertNotNull("${definition.id}: нет rxId", definition.rxId)
            assertNotNull("${definition.id}: нет DID", definition.did)
            assertNotNull("${definition.id}: нет декодера", definition.uds)
            assertTrue("${definition.id}: пустой источник", definition.source.isNotBlank())
            assertTrue(
                "${definition.id}: источник не OBDb",
                definition.source.contains("OBDb"),
            )
            assertEquals(
                "${definition.id}: заявлено подтверждение",
                Verification.DECODED,
                definition.verification,
            )
        }
    }

    @Test
    fun `headers belong to the expected modules`() {
        val byId = VagSignals.all.associateBy { it.id }
        assertEquals(0x7E0, byId.getValue("vag.7e0.1057.boost").txId)
        assertEquals(0x7E8, byId.getValue("vag.7e0.1057.boost").rxId)
        assertEquals(0x7E1, byId.getValue("vag.7e1.2104.atf_temp").txId)
        assertEquals(0x7E9, byId.getValue("vag.7e1.2104.atf_temp").rxId)
        assertEquals(0x714, byId.getValue("vag.714.202f.oil_temp").txId)
        assertEquals(0x77E, byId.getValue("vag.714.202f.oil_temp").rxId)
    }

    @Test
    fun `geometry of the known signals is not invented`() {
        val byId = VagSignals.all.associateBy { it.id }
        assertEquals(0x1057, byId.getValue("vag.7e0.1057.boost").did)
        assertEquals(0x2104, byId.getValue("vag.7e1.2104.atf_temp").did)
        assertTrue(byId.getValue("vag.7e1.2104.atf_temp").uds!!.littleEndian)
        assertEquals(-2.0, byId.getValue("vag.7e1.210f.gear").uds!!.add, 0.0)
    }

    @Test
    fun `dashboard ids exist in the catalog`() {
        val ids = VagSignals.all.map { it.id }.toSet()
        for (id in VagSignals.dashboardIds) {
            assertTrue("Нет $id", id in ids)
        }
        assertFalse(VagSignals.dashboardIds.isEmpty())
    }

    @Test
    fun `catalog exposes VAG signals through the common index`() {
        assertNotNull(SignalCatalog.get("vag.7e0.1057.boost"))
        assertTrue(SignalCatalog.uds.isNotEmpty())
        assertEquals(VagSignals.all.size, SignalCatalog.uds.size)
    }
}
