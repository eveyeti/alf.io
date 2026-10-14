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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Single-flight en memoria para las verificaciones de pago que disparan las requests públicas:
 * el polling de {@code GET /api/v2/public/reservation/{id}/status} y el force-check de
 * {@code GET /api/v2/public/reservation/{id}/transaction/force-check}. A lo sumo una verificación
 * en curso por transacción de pago, y a lo sumo {@value #MAX_CHECKS_IN_FLIGHT} en curso en total.
 *
 * <p>Sin esto, cada request entra a la transacción de {@code TicketReservationManager#forceTransactionCheck}
 * y espera el {@code FOR UPDATE} de la transacción de pago con una conexión del pool tomada, mientras
 * otra request está en la llamada HTTP al proveedor (hasta 20 s con Banchile). El frontend consulta
 * cada 2 s sin esperar la respuesta anterior: con el proveedor lento la cola crece sin límite y agota
 * el pool, y se cae el sitio entero (el "convoy"). Con el single-flight, quien llega mientras hay una
 * verificación en curso no espera el lock ni toma una conexión para esa verificación: responde de
 * inmediato con el estado actual. A diferencia del singleflight clásico, tampoco espera el resultado
 * de la verificación en curso.
 *
 * <p>El tope global cubre a muchos compradores distintos a la vez: cada verificación retiene una
 * conexión mientras espera al proveedor, así que con el proveedor lento 10 compradores bastan para
 * ocupar las 10 conexiones del pool de producción. Con el tope, a lo sumo 5 conexiones quedan
 * retenidas por verificaciones y el resto del sitio conserva las demás. Quien llega con los 5 cupos
 * ocupados responde como un poll omitido, sin esperar un cupo.
 *
 * <p>Supuesto: alf.io corre en una sola instancia (un único proceso). El registro de verificaciones
 * en curso vive en memoria; con varias instancias, cada una permitiría su propia verificación del
 * mismo pago, y su propio tope, y habría que llevarlo a la base de datos (por ejemplo, con un
 * advisory lock).
 *
 * <p>El cron de conciliación de Banchile, el stuck-check y la limpieza de reservas vencidas no pasan
 * por acá, así que tampoco cuentan para el tope: corren en el único hilo del scheduler y siguen
 * esperando el lock de la transacción.
 */
@Component
public class PaymentCheckSingleFlight {

    /** Verificaciones simultáneas permitidas desde las requests públicas, sumando todos los pagos (decisión del dueño). */
    static final int MAX_CHECKS_IN_FLIGHT = 5;

    private final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(MAX_CHECKS_IN_FLIGHT);

    /**
     * Ejecuta {@code check} si no hay otra verificación en curso para la transacción y queda un cupo
     * del tope global; si no, no espera y devuelve lo que entregue {@code whenBusy}. El cupo se toma
     * después del turno de la transacción, y los dos se liberan siempre al terminar {@code check},
     * también si lanza una excepción (la excepción se propaga tal cual).
     *
     * @param transactionId id de la transacción de pago ({@code b_transaction.id})
     * @param check         verificación a ejecutar
     * @param whenBusy      respuesta cuando ya hay una verificación en curso para la transacción o no
     *                      queda cupo
     * @param <T>           tipo del resultado
     * @return el resultado de {@code check}, o el de {@code whenBusy} si no se ejecutó
     */
    public <T> T callIfIdle(int transactionId, Supplier<T> check, Supplier<T> whenBusy) {
        if (!inFlight.add(transactionId)) {
            return whenBusy.get();
        }
        try {
            if (!slots.tryAcquire()) {
                return whenBusy.get();
            }
            try {
                return check.get();
            } finally {
                slots.release();
            }
        } finally {
            inFlight.remove(transactionId);
        }
    }

    /**
     * Como {@link #callIfIdle}, para una verificación sin resultado.
     *
     * @param transactionId id de la transacción de pago ({@code b_transaction.id})
     * @param check         verificación a ejecutar
     * @return true si se ejecutó; false si había otra verificación en curso para la transacción, o
     *         ningún cupo libre, y no se hizo nada
     */
    public boolean runIfIdle(int transactionId, Runnable check) {
        return callIfIdle(transactionId, () -> {
            check.run();
            return true;
        }, () -> false);
    }
}
