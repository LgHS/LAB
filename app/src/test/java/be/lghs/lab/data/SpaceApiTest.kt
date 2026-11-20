package be.lghs.lab.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceApiTest {
    private fun state(open: Boolean, visitors: Boolean) =
        SpaceApiRepository.parse("""{"space":"LgHS","state":{"open":$open,"open_to_visitors":$visitors}}""")

    @Test
    fun open() = assertEquals(SpaceState.OPEN, state(open = true, visitors = true))

    @Test
    fun membersOnly() = assertEquals(SpaceState.MEMBERS, state(open = true, visitors = false))

    @Test
    fun closed() = assertEquals(SpaceState.CLOSED, state(open = false, visitors = false))
}
