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

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Freno del poll de "procesando pago" ({@code GET /api/v2/public/reservation/{id}/status}): decide
 * cuándo un poll consulta al proveedor de pago. El frontend consulta cada 2 s y, sin freno, cada poll
 * con un pago Banchile pendiente llegaba a Banchile: 853 consultas en 28 minutos con una sola
 * pestaña abierta (QA del 15-oct). Entre consulta y consulta, el poll responde con el último estado
 * conocido de la reserva.
 *
 * <p>Un poll consulta si:
 * <ul>
 *   <li>es el primero de una tanda: no hubo polls del pago en los últimos
 *       {@link #NEW_POLLING_ROUND_GAP} (el comprador acaba de llegar o de volver de Banchile, con el
 *       pago quizás aprobado). Mientras el comprador está en Banchile nadie consulta, así que el primer
 *       poll al volver siempre consulta, aunque la última consulta haya sido hace menos de
 *       {@link #MIN_INTERVAL_BETWEEN_QUERIES} (volvió con "Atrás" y siguió con "Adelante"); o</li>
 *   <li>pasaron {@link #MIN_INTERVAL_BETWEEN_QUERIES} desde la consulta anterior: mientras el comprador
 *       espera en "procesando pago", el pago se consulta a lo sumo una vez cada 10 s.</li>
 * </ul>
 *
 * <p>Cuando el freno deja pasar un poll, la consulta queda registrada en ese momento aunque después
 * no se haga: si ya hay otra verificación en curso para el pago, esa es la consulta; si se acabaron
 * los cupos de {@link PaymentCheckSingleFlight}, el próximo intento espera los 10 s, que con Banchile
 * lento o con muchos compradores es justo lo que conviene. El webhook, el force-check y el cron no
 * pasan por acá.
 *
 * <p>El poll consulta el freno antes que {@link PaymentCheckSingleFlight}: un poll frenado no toma el
 * turno del pago, así que no deja sin consultar a un webhook de Banchile que llegue en ese momento
 * (con el turno tomado, el webhook no consulta y deja el pago al poll o al cron).
 *
 * <p>Como {@link PaymentCheckSingleFlight}, supone una sola instancia de alf.io: el registro vive en
 * memoria. Solo guarda los pagos con polls en los últimos {@link #NEW_POLLING_ROUND_GAP}; olvidar uno
 * más viejo no cambia nada, porque su próximo poll igual consultaría.
 */
@Component
public class PaymentPollThrottle {

    /**
     * Mínimo entre dos consultas de un mismo pago mientras el comprador espera. Con el poll cada 2 s,
     * 10 s es una consulta de cada 5 (de 900 a 180 en los 30 minutos de una sesión), y el comprador que
     * terminó de pagar en otra pestaña o en la app del banco espera a lo sumo 10 s más; el webhook de
     * Banchile y el primer poll al volver confirman antes.
     */
    public static final Duration MIN_INTERVAL_BETWEEN_QUERIES = Duration.ofSeconds(10);

    /**
     * Pausa sin polls que cierra una tanda: más que el intervalo del frontend (2 s) con margen para
     * la red del celular, y menos que lo que tarda cualquiera en pagar en Banchile y volver.
     */
    public static final Duration NEW_POLLING_ROUND_GAP = Duration.ofSeconds(5);

    /** Pagos registrados a partir de los cuales se olvidan los que no tienen polls recientes. */
    static final int PRUNE_THRESHOLD = 256;

    /**
     * Último poll y última consulta de un pago, en nanosegundos del reloj del freno.
     */
    private record Polls(long lastPollNanos, long lastQueryNanos) {}

    private final ConcurrentHashMap<Integer, Polls> pollsByTransaction = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;
    private final long minIntervalNanos = MIN_INTERVAL_BETWEEN_QUERIES.toNanos();
    private final long newRoundGapNanos = NEW_POLLING_ROUND_GAP.toNanos();

    /**
     * El freno con el reloj monotónico de la JVM ({@link System#nanoTime()}). No usa el
     * {@code ClockProvider} de alf.io: en los tests marca siempre la misma hora.
     */
    public PaymentPollThrottle() {
        this(System::nanoTime);
    }

    private PaymentPollThrottle(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /**
     * El freno con otro reloj, para los tests.
     *
     * @param nanoTime reloj monotónico en nanosegundos
     * @return un freno nuevo, sin polls registrados
     */
    public static PaymentPollThrottle withClock(LongSupplier nanoTime) {
        return new PaymentPollThrottle(nanoTime);
    }

    /**
     * Registra un poll del pago y dice si consulta al proveedor (ver la descripción de la clase). Si
     * dice que sí, registra la consulta en este momento. Entre polls simultáneos del mismo pago, a lo
     * sumo uno recibe true.
     *
     * @param transactionId id de la transacción de pago ({@code b_transaction.id})
     * @return true si el poll consulta al proveedor; false si responde con el último estado conocido
     */
    public boolean shouldQuery(int transactionId) {
        long now = nanoTime.getAsLong();
        var query = new boolean[1];
        pollsByTransaction.compute(transactionId, (id, previous) -> {
            query[0] = previous == null
                || now - previous.lastPollNanos() >= newRoundGapNanos
                || now - previous.lastQueryNanos() >= minIntervalNanos;
            return new Polls(now, query[0] ? now : previous.lastQueryNanos());
        });
        if (pollsByTransaction.size() > PRUNE_THRESHOLD) {
            // remove(key, value) por dentro: un pago que recibe un poll mientras tanto se queda
            pollsByTransaction.entrySet().removeIf(entry -> now - entry.getValue().lastPollNanos() >= newRoundGapNanos);
        }
        return query[0];
    }

    /**
     * @return cuántos pagos tiene registrados el freno (para los tests)
     */
    int trackedPayments() {
        return pollsByTransaction.size();
    }
}
