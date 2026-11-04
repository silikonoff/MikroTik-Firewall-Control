package ru.nikmadz.mikrotikcontrol
import org.junit.Test
import ru.nikmadz.mikrotikcontrol.tests.CoreChecks
class CoreTest {
    @Test fun coreAndTransportChecks() { CoreChecks.runAll() }
}
