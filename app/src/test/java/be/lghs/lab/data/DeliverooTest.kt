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
        assertNull(d.deliveredEpochSec) // ancien format sans livraison_estimee
    }

    /** Format du 2026-11-09 : livraison_estimee donne l'heure d'arrivée d'une commande livrée. */
    @Test
    fun deliveredWithArrivalTime() {
        val d = DeliverooRepository.parse(
            """{"restaurant": "Burger King - Liège Médiacité", "livraison": null, "date_livraison": "2026-11-07",
               "livraison_debut": null, "livraison_fin": null, "livraison_estimee": "2026-11-07T14:45:54+01:00",
               "code_livreur": "83", "statut": "Votre commande a mis 23 minutes à arriver. Bon appétit !",
               "commande_le": "Commandé le 7 novembre 2026", "maj": "2026-11-09 16:10:24 +0100"}""",
        )!!
        assertTrue(d.delivered)
        assertEquals(1794059154L, d.deliveredEpochSec)
    }

    @Test
    fun arrivalTimeIgnoredWhileOnTheWay() {
        val d = DeliverooRepository.parse(
            """{"restaurant": "Burger King", "livraison": "Environ 7 minutes", "livraison_estimee": "2026-11-07T14:45:54+01:00",
               "code_livreur": "83", "statut": "La commande est en cours de livraison", "maj": "2026-11-07 14:38:54 +0100"}""",
        )!!
        assertFalse(d.delivered)
        assertNull(d.deliveredEpochSec)
    }
}
