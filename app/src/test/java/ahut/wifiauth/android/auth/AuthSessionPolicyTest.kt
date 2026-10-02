package ahut.wifiauth.android.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AuthSessionPolicyTest {
    private val policy = AuthSessionPolicy()

    private fun identity(
        connectionId: String = "Network(101)",
        generation: Long = 1,
        ssid: String? = "AHUT-FREE",
        ip: String = "192.0.2.10"
    ) = RequestIdentity(connectionId, generation, ssid, ip)

    @Test
    fun responseFromNetworkABecomesStaleWhenCurrentNetworkChangesToB() {
        val networkA = identity()
        val networkB = identity(connectionId = "Network(202)", generation = 2, ssid = "AHUT-wifi6")
        policy.observe(networkA)
        assertTrue(policy.isCurrent(networkA))

        policy.observe(networkB)

        assertFalse(policy.isCurrent(networkA))
        assertFalse(policy.canAutoLogin(networkA, true, true, true, true))
        assertTrue(policy.isCurrent(networkB))
    }

    @Test
    fun logoutPauseSurvivesUnknownSnapshotsAndActivityOrTileRecreation() {
        val firstSnapshot = identity()
        policy.observe(firstSnapshot)
        policy.pauseForLogout()

        val persisted = policy.persistedState()
        val recreatedCoordinatorPolicy = AuthSessionPolicy(persisted)
        recreatedCoordinatorPolicy.observe(null) // temporary missing IPv4 / SSID or observer restart
        val restoredSnapshot = identity(ip = "192.0.2.20") // same Network, roaming IP update
        recreatedCoordinatorPolicy.observe(restoredSnapshot)

        assertTrue(recreatedCoordinatorPolicy.isUserPaused())
        assertFalse(recreatedCoordinatorPolicy.canAutoLogin(restoredSnapshot, true, true, true, true))
    }

    @Test
    fun confirmedDisconnectOrDifferentNetworkStartsANewCycle() {
        val networkA = identity()
        policy.observe(networkA)
        policy.pauseForLogout()

        assertTrue(policy.confirmNetworkLost(networkA.connectionId))
        val reconnected = identity()
        policy.observe(reconnected)
        assertFalse(policy.isUserPaused())

        policy.pauseForLogout()
        val networkB = identity(connectionId = "Network(202)", generation = 2)
        assertTrue(policy.observe(networkB))
        assertFalse(policy.isUserPaused())
    }

    @Test
    fun wifi6RequiresExplicitTargetAndAutoLoginRequiresConfirmedOffline() {
        val wifi6 = identity(ssid = "AHUT-wifi6")
        policy.observe(wifi6)
        assertFalse(policy.canAutoLogin(wifi6, true, false, true, true))
        assertFalse(policy.canAutoLogin(wifi6, true, true, true, false))
        assertTrue(policy.canAutoLogin(wifi6, true, true, true, true))
        assertFalse(policy.canAutoLogin(identity(ssid = "ahut-wifi6"), true, true, true, true))
    }

    @Test
    fun transientErrorsReceiveOnlyFiveAndFifteenSecondRetryBudget() {
        val current = identity()
        policy.observe(current)

        assertEquals(5_000L, policy.recordTemporaryNetworkError(current))
        assertEquals(15_000L, policy.recordTemporaryNetworkError(current))
        assertEquals(null, policy.recordTemporaryNetworkError(current))
        assertFalse(policy.canAutoLogin(current, true, true, true, true))
    }

    @Test
    fun persistedPauseAndExhaustedRetryBudgetSurviveCoordinatorRecreation() {
        val current = identity()
        policy.observe(current)
        policy.pauseForLogout()
        val pausedAgain = AuthSessionPolicy(policy.persistedState())
        pausedAgain.observe(current)
        assertTrue(pausedAgain.isUserPaused())
        assertFalse(pausedAgain.canAutoLogin(current, true, true, true, true))

        val retryPolicy = AuthSessionPolicy()
        retryPolicy.observe(current)
        retryPolicy.recordTemporaryNetworkError(current)
        retryPolicy.recordTemporaryNetworkError(current)
        retryPolicy.recordTemporaryNetworkError(current)
        val exhaustedAgain = AuthSessionPolicy(retryPolicy.persistedState())
        exhaustedAgain.observe(current)
        assertFalse(exhaustedAgain.canAutoLogin(current, true, true, true, true))
    }

    @Test
    fun lateManualLoginCannotUndoANewerLogoutIntent() {
        val gate = AuthIntentGate()
        val loginIntent = gate.beginIntent()
        val logoutIntent = gate.beginIntent()

        var loginCommitted = false
        var logoutCommitted = false
        assertFalse(gate.commitIfCurrent(loginIntent) { loginCommitted = true })
        assertTrue(gate.commitIfCurrent(logoutIntent) { logoutCommitted = true })
        assertFalse(loginCommitted)
        assertTrue(logoutCommitted)
    }

    @Test
    fun sharedLoginLogoutSerializerNeverRunsBothCriticalSectionsTogether() = runBlocking {
        val serializer = AuthOperationSerializer()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val operations = List(12) {
            async(Dispatchers.Default) {
                serializer.withLock {
                    val now = active.incrementAndGet()
                    maximum.updateAndGet { previous -> maxOf(previous, now) }
                    delay(5)
                    active.decrementAndGet()
                }
            }
        }
        operations.forEach { it.await() }
        assertEquals(1, maximum.get())
    }
}
