package be.lghs.lab.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Réponses réelles de https://lghs.be/deliveroo/?json au fil d'une commande. */
class DeliverooTest {
    private fun order(livraison: String?, statut: String, maj: String) = DeliverooRepository.parse(
        """{"restaurant": "Bro's Burger Kitchen - Liège",
           "livraison": ${livraison?.let { "\"$it\"" } ?: "null"},
           "code_livreur": "83", "statut": "$statut", "maj": "$maj"}""",
    )!!

    /** 2026-09-30 20:57:03 +0200 */
    private val at205703 = 1790794623L

    @Test
    fun noOrder() {
        assertNull(DeliverooRepository.parse("null"))
        assertNull(DeliverooRepository.parse("""{"erreur": "Statut de commande introuvable"}"""))
        assertNull(DeliverooRepository.parse("""{"livraison": null, "code_livreur": null, "statut": null}"""))
    }

    @Test
    fun preparing() {
        val d = order("20:55–21:10", "Bro's Burger Kitchen - Liège prépare votre commande", "2026-09-30 20:50:26 +0200")
        assertEquals("83", d.code)
        assertEquals("Prépare votre commande", d.status) // restaurant retiré du statut
        assertEquals("20:55–21:10", d.window)
        assertNull(d.etaEpochSec) // créneau : affiché tel quel
        assertFalse(d.near)
        assertFalse(d.delivered)
    }

    @Test
    fun onTheWayGivesAnArrivalTime() {
        val d = order("Environ 7 minutes", "La commande est en cours de livraison", "2026-09-30 20:57:03 +0200")
        assertEquals(at205703, d.updatedEpochSec)
        assertEquals(at205703 + 7 * 60, d.etaEpochSec)
        assertFalse(d.near)
    }

    @Test
    fun nearFromEtaOrStatus() {
        assertTrue(order("1 minute", "La commande est en cours de livraison", "2026-09-30 21:00:43 +0200").near)
        val proche = order("Moins d'une minute", "Livreur à proximité", "2026-09-30 21:01:45 +0200")
        assertTrue(proche.near)
        assertEquals(proche.updatedEpochSec, proche.etaEpochSec)
    }

    @Test
    fun delivered() {
        val d = order(null, "Votre commande a mis 23 minutes à arriver. Bon appétit !", "2026-09-30 21:02:52 +0200")
        assertTrue(d.delivered)
        assertFalse(d.near)
        assertNull(d.etaEpochSec)
    }
}
