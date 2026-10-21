package id.nusacred.contracts
import kotlin.test.*
class StateTest {
    @Test fun terminalStatesCannotReplay() {
        for(state in listOf(ExchangeState.COMPLETED,ExchangeState.DECLINED,ExchangeState.EXPIRED))
            for(next in ExchangeState.entries) assertFalse(state.canTransitionTo(next))
        assertTrue(ExchangeState.PREVIEWED.canTransitionTo(ExchangeState.ACCEPTED))
        assertFalse(ExchangeState.PREVIEWED.canTransitionTo(ExchangeState.COMPLETED))
        assertTrue(ExchangeState.EXECUTING.canTransitionTo(ExchangeState.RECONCILE_REQUIRED))
    }
}
