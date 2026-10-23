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
package alfio.job;

import alfio.manager.payment.PaymentPollThrottle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reloj del freno del poll ({@link PaymentPollThrottle}) en los tests de integración: así un test
 * hace dos consultas seguidas del mismo pago sin esperar 10 s de verdad. Un contexto lo usa listando
 * una de sus dos configuraciones en su {@code @ContextConfiguration}:
 * <ul>
 *   <li>{@link Manual}: solo avanza cuando el test lo adelanta. Ninguna decisión del freno depende de
 *       cuánto tarde la máquina, así que los tests que cuentan consultas son deterministas;</li>
 *   <li>{@link FollowingRealTime}: el reloj real más lo adelantado, para los escenarios que reproducen
 *       el ritmo real del frontend (el convoy de polls cada 2 s).</li>
 * </ul>
 *
 * <p>Vive en {@code alfio.job}, paquete que ningún {@code @ComponentScan} recorre: solo lo usan los
 * contextos que lo listan.
 */
public final class PollThrottleTestClock {

    private final boolean followsRealTime;
    private final AtomicLong offsetNanos = new AtomicLong();

    private PollThrottleTestClock(boolean followsRealTime) {
        this.followsRealTime = followsRealTime;
    }

    /**
     * @return la hora del freno en nanosegundos: lo adelantado, más el reloj monotónico real si sigue la hora real
     */
    public long nanos() {
        return (followsRealTime ? System.nanoTime() : 0L) + offsetNanos.get();
    }

    /**
     * Adelanta el reloj del freno.
     *
     * @param duration cuánto
     */
    public void advance(Duration duration) {
        offsetNanos.addAndGet(duration.toNanos());
    }

    /**
     * Adelanta el reloj lo suficiente para que el próximo poll de cualquier pago consulte.
     */
    public void letTheNextPollQuery() {
        advance(PaymentPollThrottle.MIN_INTERVAL_BETWEEN_QUERIES);
    }

    private static PaymentPollThrottle throttleWith(PollThrottleTestClock clock) {
        return PaymentPollThrottle.withClock(clock::nanos);
    }

    /**
     * El freno con un reloj que solo avanza cuando el test lo adelanta.
     */
    @Configuration
    public static class Manual {

        @Bean
        PollThrottleTestClock pollThrottleTestClock() {
            return new PollThrottleTestClock(false);
        }

        @Bean
        @Primary
        PaymentPollThrottle paymentPollThrottleWithManualClock(PollThrottleTestClock clock) {
            return throttleWith(clock);
        }
    }

    /**
     * El freno con el reloj real más lo que el test adelante.
     */
    @Configuration
    public static class FollowingRealTime {

        @Bean
        PollThrottleTestClock pollThrottleTestClock() {
            return new PollThrottleTestClock(true);
        }

        @Bean
        @Primary
        PaymentPollThrottle paymentPollThrottleFollowingRealTime(PollThrottleTestClock clock) {
            return throttleWith(clock);
        }
    }
}
