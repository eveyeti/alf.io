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
import alfio.model.transaction.PaymentProxy;
import alfio.model.transaction.Transaction;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 */
@Component
@AllArgsConstructor
public class BanchileReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(BanchileReconciliationJob.class);
    private static final long TWELVE_MINUTES_MS = 12L * 60L * 1000L;

    private final TransactionRepository transactionRepository;
    private final TicketReservationManager ticketReservationManager;
    private final TicketReservationRepository ticketReservationRepository;
    private final PurchaseContextManager purchaseContextManager;

    @Scheduled(fixedRate = TWELVE_MINUTES_MS)
    public void reconcilePendingBanchileTransactions() {
        var pendingTransactions = transactionRepository.findByStatusAndPaymentProxy(
            Transaction.Status.PENDING, PaymentProxy.BANCHILE
        );

        if (pendingTransactions.isEmpty()) {
            log.trace("[banchile-cron] no hay transacciones BANCHILE en PENDING");
            return;
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
                if (result.isPresent()) {
                    log.info("[banchile-cron] reservation={} forceCheck result={}",
                        tx.getReservationId(), result.get());
                    reconciled++;
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
    }
}
