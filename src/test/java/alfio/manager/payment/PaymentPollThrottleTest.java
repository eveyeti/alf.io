/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El freno del poll de "procesando pago": el navegador consulta cada 2 s y, sin freno, cada consulta
 * llegaba a Banchile (853 llamadas en 28 minutos con una sola pestaña). Con el freno, se consulta a
 * Banchile en el primer poll de una tanda (el comprador acaba de llegar o de volver de Banchile) y
 * después a lo sumo cada 10 s. El reloj es falso: ninguna aserción depende de cuánto tarde la máquina.
 */
class PaymentPollThrottleTest {

    private static final int TRANSACTION_ID = 112;
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final PaymentPollThrottle throttle = PaymentPollThrottle.withClock(now::get);
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void theFirstPollOfAPaymentQueriesTheProvider() {
        assertTrue(throttle.shouldQuery(TRANSACTION_ID));
    }

    @Test
    void whileTheBuyerWaitsThePollQueriesAtMostEveryTenSeconds() {
        var queriedAt = new ArrayList<Long>();
        // el frontend consulta cada 2 s durante un minuto
        for (long second = 0; second <= 60; second += POLL_INTERVAL.toSeconds()) {
            if (throttle.shouldQuery(TRANSACTION_ID)) {
                queriedAt.add(second);
            }
            advance(POLL_INTERVAL);
        }

        assertEquals(List.of(0L, 10L, 20L, 30L, 40L, 50L, 60L), queriedAt);
    }

    @Test
    void betweenQueriesThePollDoesNotQuery() {
        assertTrue(throttle.shouldQuery(TRANSACTION_ID));
        for (int i = 0; i < 4; i++) {
            advance(POLL_INTERVAL);
            assertFalse(throttle.shouldQuery(TRANSACTION_ID));
        }
        advance(Duration.ofMillis(1_999));
        assertFalse(throttle.shouldQuery(TRANSACTION_ID), "a los 9,999 s todavía no toca");
        advance(Duration.ofMillis(1));
        assertTrue(throttle.shouldQuery(TRANSACTION_ID), "a los 10 s sí");
    }

    /**
     * El comprador vuelve de Banchile con el pago aprobado: mientras estaba allá nadie consultaba, así
     * que el primer poll después de volver consulta aunque la última consulta haya sido hace menos de
     * 10 s (por ejemplo, volvió con "Atrás", "procesando pago" consultó, y siguió con "Adelante").
     */
    @Test
    void theFirstPollAfterAPauseQueriesEvenIfTheLastQueryWasLessThanTenSecondsAgo() {
        assertTrue(throttle.shouldQuery(TRANSACTION_ID));
        advance(POLL_INTERVAL);
        assertFalse(throttle.shouldQuery(TRANSACTION_ID));
        // se va a Banchile: 5 s sin polls
        advance(PaymentPollThrottle.NEW_POLLING_ROUND_GAP);

        assertTrue(throttle.shouldQuery(TRANSACTION_ID), "el primer poll al volver consulta");
        advance(POLL_INTERVAL);
        assertFalse(throttle.shouldQuery(TRANSACTION_ID), "y los siguientes vuelven a esperar sus 10 s");
    }

    @Test
    void aShorterPauseIsStillTheSameRoundOfPolls() {
        assertTrue(throttle.shouldQuery(TRANSACTION_ID));
        advance(PaymentPollThrottle.NEW_POLLING_ROUND_GAP.minusMillis(1));

        assertFalse(throttle.shouldQuery(TRANSACTION_ID));
    }

    /**
     * Un poll frenado también cuenta como poll: una pestaña que consulta cada 2 s no arma una tanda
     * nueva en cada poll, y el comprador que vuelve de Banchile mientras otra pestaña sigue en
     * "procesando pago" espera, como mucho, los 10 s.
     */
    @Test
    void aThrottledPollKeepsTheRoundGoing() {
        assertTrue(throttle.shouldQuery(TRANSACTION_ID));
        for (int i = 0; i < 4; i++) {
            advance(POLL_INTERVAL);
            assertFalse(throttle.shouldQuery(TRANSACTION_ID), "poll " + i);
        }
        advance(POLL_INTERVAL);
        assertTrue(throttle.shouldQuery(TRANSACTION_ID), "a los 10 s");
    }

    @Test
    void eachPaymentHasItsOwnClock() {
        assertTrue(throttle.shouldQuery(TRANSACTION_ID));
        assertTrue(throttle.shouldQuery(TRANSACTION_ID + 1), "otro pago consulta aunque el primero acabe de consultar");
        assertFalse(throttle.shouldQuery(TRANSACTION_ID));
        assertFalse(throttle.shouldQuery(TRANSACTION_ID + 1));
    }

    @Test
    void onlyOneOfManySimultaneousPollsQueries() throws Exception {
        int polls = 16;
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        for (int i = 0; i < polls; i++) {
            results.add(executor.submit(() -> {
                start.await();
                return throttle.shouldQuery(TRANSACTION_ID);
            }));
        }

        start.countDown();
        int queried = 0;
        for (var result : results) {
            if (result.get(5, TimeUnit.SECONDS)) {
                queried++;
            }
        }

        assertEquals(1, queried, "un solo poll de los simultáneos consulta");
    }

    /**
     * El registro no crece sin límite: los pagos que no se consultan desde hace más que la pausa de
     * una tanda se olvidan, y olvidarlos no cambia nada (su próximo poll igual consulta).
     */
    @Test
    void paymentsWithoutRecentPollsAreForgotten() {
        for (int id = 0; id < PaymentPollThrottle.PRUNE_THRESHOLD; id++) {
            assertTrue(throttle.shouldQuery(id));
        }
        advance(PaymentPollThrottle.NEW_POLLING_ROUND_GAP);
        assertTrue(throttle.shouldQuery(PaymentPollThrottle.PRUNE_THRESHOLD));

        assertEquals(1, throttle.trackedPayments(), "solo queda el pago con un poll reciente");
        assertTrue(throttle.shouldQuery(0), "un pago olvidado consulta en su próximo poll, como antes de olvidarlo");
    }

    @Test
    void thePolicyIsTenSecondsBetweenQueriesAndFiveSecondsOfPause() {
        assertEquals(Duration.ofSeconds(10), PaymentPollThrottle.MIN_INTERVAL_BETWEEN_QUERIES);
        assertEquals(Duration.ofSeconds(5), PaymentPollThrottle.NEW_POLLING_ROUND_GAP);
    }

    private void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
    }
}
