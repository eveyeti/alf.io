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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Una sola verificación de pago en curso por transacción, y a lo sumo 5 en curso en total: quien
 * llega mientras hay una en curso para su transacción, o con los 5 cupos ocupados, no espera ni
 * consulta.
 */
class PaymentCheckSingleFlightTest {

    private static final int TRANSACTION_ID = 112;
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final PaymentCheckSingleFlight singleFlight = new PaymentCheckSingleFlight();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void aSecondCheckOfTheSameTransactionDoesNotRunNorWaitWhileTheFirstIsInFlight() throws Exception {
        var release = new CountDownLatch(1);
        var first = checkInFlight(TRANSACTION_ID, release);

        var secondRan = new AtomicBoolean();
        var second = singleFlight.callIfIdle(TRANSACTION_ID, () -> {
            secondRan.set(true);
            return "segunda";
        }, () -> "en curso");

        assertEquals("en curso", second);
        assertFalse(secondRan.get(), "la segunda verificación no debía ejecutarse");
        // la primera sigue retenida hasta que este hilo abra release: si la segunda la hubiera
        // esperado, recién volvería cuando la primera terminara
        assertFalse(first.isDone(), "la segunda debía volver mientras la primera seguía en curso, sin esperarla");
        release.countDown();
        assertEquals("primera", first.get(WAIT.toSeconds(), TimeUnit.SECONDS));
    }

    @Test
    void checksOfDifferentTransactionsDoNotBlockEachOther() throws Exception {
        var release = new CountDownLatch(1);
        var first = checkInFlight(TRANSACTION_ID, release);

        assertEquals("otra", singleFlight.callIfIdle(TRANSACTION_ID + 1, () -> "otra", () -> "en curso"));

        release.countDown();
        first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    void theNextCheckRunsOnceTheCheckInFlightEnds() throws Exception {
        var release = new CountDownLatch(1);
        var first = checkInFlight(TRANSACTION_ID, release);
        assertEquals("en curso", singleFlight.callIfIdle(TRANSACTION_ID, () -> "segunda", () -> "en curso"));

        release.countDown();
        first.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertEquals("tercera", singleFlight.callIfIdle(TRANSACTION_ID, () -> "tercera", () -> "en curso"));
    }

    @Test
    void theTurnIsReleasedWhenTheCheckThrows() {
        var failure = new IllegalStateException("Banchile no respondió");

        var thrown = assertThrows(IllegalStateException.class, () -> singleFlight.callIfIdle(TRANSACTION_ID, () -> {
            throw failure;
        }, () -> "en curso"));

        assertSame(failure, thrown, "la excepción de la verificación se propaga tal cual");
        assertEquals("siguiente", singleFlight.callIfIdle(TRANSACTION_ID, () -> "siguiente", () -> "en curso"));
    }

    @Test
    void runIfIdleTellsWhetherTheCheckRan() throws Exception {
        var release = new CountDownLatch(1);
        var first = checkInFlight(TRANSACTION_ID, release);
        var ran = new AtomicInteger();

        assertFalse(singleFlight.runIfIdle(TRANSACTION_ID, ran::incrementAndGet), "había una verificación en curso");
        release.countDown();
        first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertTrue(singleFlight.runIfIdle(TRANSACTION_ID, ran::incrementAndGet));

        assertEquals(1, ran.get());
    }

    @Test
    void onlyOneOfManySimultaneousCallersRunsTheCheck() throws Exception {
        int callers = 16;
        var start = new CountDownLatch(1);
        var runs = new AtomicInteger();
        // la verificación que corre sigue en curso hasta que las otras 15 llamadas hayan vuelto:
        // así ninguna puede llegar cuando ya terminó, por lenta que ande la máquina
        var othersReturned = new CountDownLatch(callers - 1);
        var results = new ArrayList<Future<String>>();
        for (int i = 0; i < callers; i++) {
            results.add(executor.submit(() -> {
                start.await();
                var result = singleFlight.callIfIdle(TRANSACTION_ID, () -> {
                    runs.incrementAndGet();
                    await(othersReturned);
                    return "consultó";
                }, () -> "en curso");
                if ("en curso".equals(result)) {
                    othersReturned.countDown();
                }
                return result;
            }));
        }

        start.countDown();
        int queried = 0;
        for (var result : results) {
            if ("consultó".equals(result.get(WAIT.toSeconds(), TimeUnit.SECONDS))) {
                queried++;
            }
        }

        assertEquals(1, runs.get(), "solo una de las llamadas simultáneas debía ejecutar la verificación");
        assertEquals(1, queried);
    }

    // -------------------------------------------------------------------------
    // Tope global: a lo sumo 5 verificaciones en curso, sumando todas las transacciones
    // -------------------------------------------------------------------------

    @Test
    void atMostFiveChecksOfDifferentTransactionsRunAtOnce() throws Exception {
        var release = new CountDownLatch(1);
        var inFlight = fiveChecksInFlight(release);

        var sixthRan = new AtomicBoolean();
        var sixth = singleFlight.callIfIdle(TRANSACTION_ID + 5, () -> {
            sixthRan.set(true);
            return "sexta";
        }, () -> "tope");

        assertEquals("tope", sixth, "con 5 verificaciones en curso, la sexta responde como un poll omitido");
        assertFalse(sixthRan.get(), "la sexta verificación no debía ejecutarse");
        // las 5 siguen retenidas hasta que este hilo abra release: la sexta no esperó un cupo
        for (var check : inFlight) {
            assertFalse(check.isDone(), "la sexta debía volver mientras las 5 seguían en curso, sin esperar un cupo");
        }
        release.countDown();
        for (var check : inFlight) {
            assertEquals("primera", check.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        }
    }

    /**
     * Un rechazo por el tope no libera el cupo que no tomó: si lo liberara, cada rechazo agrandaría
     * el tope en uno y, después de unos cuantos, dejaría pasar cualquier cantidad de verificaciones.
     */
    @Test
    void aCheckRejectedByTheGlobalLimitDoesNotFreeASlotItDidNotTake() throws Exception {
        var release = new CountDownLatch(1);
        var inFlight = fiveChecksInFlight(release);
        assertEquals("tope", singleFlight.callIfIdle(TRANSACTION_ID + 5, () -> "sexta", () -> "tope"));

        var seventhRan = new AtomicBoolean();
        var seventh = singleFlight.callIfIdle(TRANSACTION_ID + 6, () -> {
            seventhRan.set(true);
            return "séptima";
        }, () -> "tope");

        assertEquals("tope", seventh, "las 5 siguen en curso: el rechazo de la sexta no debía dejar un cupo libre");
        assertFalse(seventhRan.get(), "la séptima verificación no debía ejecutarse");
        release.countDown();
        for (var check : inFlight) {
            check.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        // terminadas las 5, el tope sigue siendo 5: entran otras 5 y la sexta vuelve a quedar afuera
        var releaseAgain = new CountDownLatch(1);
        var fiveMore = new ArrayList<Future<String>>();
        for (int i = 10; i < 15; i++) {
            fiveMore.add(checkInFlight(TRANSACTION_ID + i, releaseAgain));
        }
        assertEquals("tope", singleFlight.callIfIdle(TRANSACTION_ID + 15, () -> "sexta", () -> "tope"),
            "el tope no debía crecer con los rechazos");
        releaseAgain.countDown();
        for (var check : fiveMore) {
            check.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    @Test
    void aFreedSlotLetsTheNextCheckRun() throws Exception {
        var releaseFirst = new CountDownLatch(1);
        var releaseOthers = new CountDownLatch(1);
        var first = checkInFlight(TRANSACTION_ID, releaseFirst);
        var others = new ArrayList<Future<String>>();
        for (int i = 1; i < 5; i++) {
            others.add(checkInFlight(TRANSACTION_ID + i, releaseOthers));
        }
        assertEquals("tope", singleFlight.callIfIdle(TRANSACTION_ID + 5, () -> "sexta", () -> "tope"));

        releaseFirst.countDown();
        first.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertEquals("sexta", singleFlight.callIfIdle(TRANSACTION_ID + 5, () -> "sexta", () -> "tope"),
            "terminada una de las 5, la siguiente verificación toma el cupo libre");
        releaseOthers.countDown();
        for (var check : others) {
            check.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    @Test
    void theSlotIsReleasedWhenTheCheckThrows() {
        for (int i = 0; i < 5; i++) {
            int transactionId = TRANSACTION_ID + i;
            assertThrows(IllegalStateException.class, () -> singleFlight.callIfIdle(transactionId, () -> {
                throw new IllegalStateException("Banchile no respondió");
            }, () -> "tope"));
        }

        assertEquals("sexta", singleFlight.callIfIdle(TRANSACTION_ID + 5, () -> "sexta", () -> "tope"),
            "5 verificaciones que fallaron no deben dejar ocupados los 5 cupos");
    }

    @Test
    void aCheckRejectedByTheGlobalLimitDoesNotKeepItsTransactionBusy() throws Exception {
        var releaseFirst = new CountDownLatch(1);
        var releaseOthers = new CountDownLatch(1);
        var first = checkInFlight(TRANSACTION_ID, releaseFirst);
        var others = new ArrayList<Future<String>>();
        for (int i = 1; i < 5; i++) {
            others.add(checkInFlight(TRANSACTION_ID + i, releaseOthers));
        }
        int rejected = TRANSACTION_ID + 99;
        assertEquals("tope", singleFlight.callIfIdle(rejected, () -> "consultó", () -> "tope"));

        releaseFirst.countDown();
        first.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertEquals("consultó", singleFlight.callIfIdle(rejected, () -> "consultó", () -> "tope"),
            "el rechazo por el tope no debe dejar la transacción marcada como en curso");
        releaseOthers.countDown();
        for (var check : others) {
            check.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    /**
     * Cinco verificaciones de transacciones distintas en curso, cada una en su hilo, hasta que se abra {@code release}.
     */
    private List<Future<String>> fiveChecksInFlight(CountDownLatch release) throws InterruptedException {
        var checks = new ArrayList<Future<String>>();
        for (int i = 0; i < 5; i++) {
            checks.add(checkInFlight(TRANSACTION_ID + i, release));
        }
        return checks;
    }

    /**
     * Verificación de la transacción que queda en curso, en otro hilo, hasta que se abra {@code release}.
     */
    private Future<String> checkInFlight(int transactionId, CountDownLatch release) throws InterruptedException {
        var started = new CountDownLatch(1);
        var future = executor.submit(() -> singleFlight.callIfIdle(transactionId, () -> {
            started.countDown();
            try {
                assertTrue(release.await(WAIT.toSeconds(), TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return "primera";
        }, () -> "en curso"));
        assertTrue(started.await(WAIT.toSeconds(), TimeUnit.SECONDS), "la primera verificación no empezó");
        return future;
    }

    /**
     * Espera a que se abra {@code latch}, con el tope de seguridad {@link #WAIT}.
     */
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(WAIT.toSeconds(), TimeUnit.SECONDS), "no volvieron todas las llamadas");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
