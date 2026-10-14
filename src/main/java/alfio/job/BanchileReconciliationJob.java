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

import alfio.manager.PurchaseContextManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.payment.BanchilePagosWebhookManager;
import alfio.manager.payment.BanchilePagosWebhookManager.InvalidatedSessionReview;
import alfio.model.transaction.PaymentProxy;
import alfio.model.transaction.Transaction;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.util.ClockProvider;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * Cronjob específico para Banchile Pagos: cada 12 minutos consulta todas las
 * transacciones BANCHILE en estado PENDING y dispara {@code forceTransactionCheck}
 * para cada una, asignándoles el estado final consultando la API de Banchile.
 *
 * <p>Cumple el requerimiento de la certificación de Banchile (subtipo Personalizada):
 * <em>"Implementar una tarea programada (cronjob) que se ejecute automáticamente cada
 * 12 minutos para consultar todas las transacciones en estado pendiente y asignarles
 * un estado final (aprobado, rechazado u otro)."</em>
 *
 * <p>Esta es una red de seguridad adicional al polling server-side del endpoint
 * {@code /api/v2/public/reservation/{id}/status} y al webhook entrante en
 * {@code /api/payment/webhook/banchile}. Cubre el caso en que ambos fallen (frontend
 * desconectado + webhook caído / no registrado).
 *
 * <p>Antes de las PENDING revisa las sesiones que alf.io dejó INVALID al crear otra para la misma
 * reserva: Banchile no las cancela y el comprador puede pagarlas (ver
 * {@link BanchilePagosWebhookManager#reviewInvalidatedSession}).
 */
@Component
@AllArgsConstructor
public class BanchileReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(BanchileReconciliationJob.class);
    private static final long TWELVE_MINUTES_MS = 12L * 60L * 1000L;
    /** Espera tras el arranque: sin ella el primer run ocurre en t=0, sin ventana para observarlo. */
    private static final long TWO_MINUTES_MS = 2L * 60L * 1000L;

    private final TransactionRepository transactionRepository;
    private final TicketReservationManager ticketReservationManager;
    private final TicketReservationRepository ticketReservationRepository;
    private final PurchaseContextManager purchaseContextManager;
    private final BanchilePagosWebhookManager banchilePagosWebhookManager;
    private final TransactionTemplate transactionTemplate;
    private final ClockProvider clockProvider;

    /**
     * Revisa en Banchile las sesiones invalidadas recientes y después consulta cada transacción
     * BANCHILE en PENDING. Qué se hace con la reserva lo decide
     * {@code BanchilePagosWebhookManager#forceTransactionCheck}.
     *
     * <p>Las invalidadas van primero: si la PENDING de la misma reserva viene rechazada y la reserva
     * está vencida, el flujo de pago fallido de alf.io la anula y borra todas sus transacciones,
     * también la de la sesión invalidada que el comprador pudo haber pagado. Si su revisión falla
     * entera, el run sigue con las PENDING: su conciliación es la que exige la certificación.
     *
     * @return cantidad de transacciones que dejaron de estar PENDING en esta ejecución (no cuenta
     *         las que Banchile sigue informando como pendientes ni las que fallaron al consultar, ni
     *         las sesiones invalidadas revisadas)
     */
    @Scheduled(fixedRate = TWELVE_MINUTES_MS, initialDelay = TWO_MINUTES_MS)
    public int reconcilePendingBanchileTransactions() {
        try {
            reviewInvalidatedSessions();
        } catch (RuntimeException e) {
            // por ejemplo, sin conexiones en el pool para buscarlas: cada sesión ya tiene su propio manejo de errores
            log.error("[banchile-cron] falló la revisión de las sesiones BANCHILE invalidadas; se sigue con las PENDING", e);
        }

        var pendingTransactions = transactionRepository.findByStatusAndPaymentProxy(
            Transaction.Status.PENDING, PaymentProxy.BANCHILE
        );

        if (pendingTransactions.isEmpty()) {
            log.trace("[banchile-cron] no hay transacciones BANCHILE en PENDING");
            return 0;
        }

        log.info("[banchile-cron] revisando {} transacciones BANCHILE en PENDING", pendingTransactions.size());
        int reconciled = 0;
        int errors = 0;

        for (var tx : pendingTransactions) {
            try {
                var reservationOpt = ticketReservationRepository.findOptionalReservationById(tx.getReservationId());
                var purchaseContextOpt = purchaseContextManager.findByReservationId(tx.getReservationId());

                if (reservationOpt.isEmpty() || purchaseContextOpt.isEmpty()) {
                    log.warn("[banchile-cron] transacción {} sin reservation o purchaseContext (reservationId={})",
                        tx.getId(), tx.getReservationId());
                    continue;
                }

                var result = ticketReservationManager.forceTransactionCheck(
                    purchaseContextOpt.get(), reservationOpt.get()
                );
                // el resultado no distingue "Banchile sigue PENDING" ni un error de "transacción
                // cerrada sin tocar la reserva": el cambio real es que la transacción dejó PENDING
                boolean changed = transactionRepository.loadOptionalByIdAndStatus(tx.getId(), Transaction.Status.PENDING).isEmpty();
                if (changed) {
                    log.info("[banchile-cron] reservation={} conciliada, forceCheck result={}",
                        tx.getReservationId(), result.orElse(null));
                    reconciled++;
                } else {
                    log.debug("[banchile-cron] reservation={} sigue PENDING, forceCheck result={}",
                        tx.getReservationId(), result.orElse(null));
                }
            } catch (Exception e) {
                errors++;
                log.error("[banchile-cron] error reconciliando transaction id={} reservationId={}",
                    tx.getId(), tx.getReservationId(), e);
            }
        }

        if (reconciled > 0 || errors > 0) {
            log.info("[banchile-cron] reconciliación finalizada — reconciled={} errors={}", reconciled, errors);
        }
        return reconciled;
    }

    /**
     * Revisa las transacciones BANCHILE en INVALID creadas en los últimos
     * {@link BanchilePagosWebhookManager#INVALIDATED_SESSION_WINDOW} que todavía no tienen un
     * resultado final. Cada una en su propia transacción de base de datos, con el mismo lock por fila
     * ({@code FOR UPDATE}) que las PENDING: un error o un timeout de Banchile no deja nada a medias y
     * no frena a las demás.
     */
    private void reviewInvalidatedSessions() {
        var now = ZonedDateTime.now(clockProvider.getClock());
        var createdSince = now.minus(BanchilePagosWebhookManager.INVALIDATED_SESSION_WINDOW);
        var sessions = transactionRepository.findInvalidByPaymentProxyCreatedSince(PaymentProxy.BANCHILE, createdSince).stream()
            .filter(tx -> !BanchilePagosWebhookManager.isInvalidatedSessionReviewed(tx))
            .toList();
        if (sessions.isEmpty()) {
            log.trace("[banchile-cron] no hay sesiones BANCHILE invalidadas por revisar");
            return;
        }

        log.info("[banchile-cron] revisando {} sesiones BANCHILE invalidadas", sessions.size());
        int alerted = 0;
        int errors = 0;
        for (var tx : sessions) {
            boolean lastReview = isLastReviewInTheWindow(tx, now);
            try {
                var purchaseContext = purchaseContextManager.findByReservationId(tx.getReservationId());
                if (purchaseContext.isEmpty()) {
                    log.warn("[banchile-cron] sesión invalidada {} sin purchaseContext (reservationId={})", tx.getId(), tx.getReservationId());
                    continue;
                }
                var review = transactionTemplate.execute(status -> transactionRepository
                    .loadOptionalByIdAndStatusForUpdate(tx.getId(), Transaction.Status.INVALID)
                    .map(locked -> banchilePagosWebhookManager.reviewInvalidatedSession(locked, purchaseContext.get(), lastReview))
                    .orElse(null));
                if (review == InvalidatedSessionReview.ALERTED) {
                    alerted++;
                }
                log.debug("[banchile-cron] sesión invalidada requestId={} reservation={}: {}", tx.getPaymentId(), tx.getReservationId(), review);
            } catch (Exception e) {
                errors++;
                log.error("[banchile-cron] error revisando la sesión invalidada id={} requestId={} reservationId={}{}",
                    tx.getId(), tx.getPaymentId(), tx.getReservationId(),
                    lastReview ? ": era su última revisión y sale de la ventana sin estado final, alf.io no la volverá a consultar" : "", e);
            }
        }
        log.info("[banchile-cron] sesiones invalidadas revisadas={} alertadas={} errors={}", sessions.size(), alerted, errors);
    }

    /**
     * true si el run siguiente, 12 minutos después, ya no revisa la sesión porque
     * para entonces salió de {@link BanchilePagosWebhookManager#INVALIDATED_SESSION_WINDOW}.
     *
     * @param session sesión invalidada, con {@code t_timestamp} igual a su creación
     * @param now     el instante con que este run calculó la ventana
     */
    private static boolean isLastReviewInTheWindow(Transaction session, ZonedDateTime now) {
        return session.getTimestamp().plus(BanchilePagosWebhookManager.INVALIDATED_SESSION_WINDOW)
            .isBefore(now.plus(Duration.ofMillis(TWELVE_MINUTES_MS)));
    }
}
