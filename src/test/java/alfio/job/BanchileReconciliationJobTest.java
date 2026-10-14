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
import alfio.manager.support.PaymentResult;
import alfio.model.PurchaseContext;
import alfio.model.TicketReservation;
import alfio.model.transaction.PaymentProxy;
import alfio.model.transaction.Transaction;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.test.util.TestUtil;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * El cron de conciliación de Banchile sin base de datos ni Banchile: lo que hace cuando falla una
 * de sus partes.
 */
class BanchileReconciliationJobTest {

    private static final String RESERVATION_ID = "f37e2446-bd03-47d2-a534-35412e6e5ca9";

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final TicketReservationManager ticketReservationManager = mock(TicketReservationManager.class);
    private final TicketReservationRepository ticketReservationRepository = mock(TicketReservationRepository.class);
    private final PurchaseContextManager purchaseContextManager = mock(PurchaseContextManager.class);
    private final BanchilePagosWebhookManager banchilePagosWebhookManager = mock(BanchilePagosWebhookManager.class);
    private final BanchileReconciliationJob job = new BanchileReconciliationJob(transactionRepository, ticketReservationManager,
        ticketReservationRepository, purchaseContextManager, banchilePagosWebhookManager, mock(TransactionTemplate.class),
        TestUtil.clockProvider());

    /**
     * La conciliación de las transacciones PENDING es la que exige la certificación de Banchile: si
     * la revisión de las sesiones invalidadas falla entera (por ejemplo, sin conexiones en el pool),
     * el run sigue con las PENDING igual.
     */
    @Test
    void aFailureReviewingTheInvalidatedSessionsDoesNotSkipThePendingTransactions() {
        when(transactionRepository.findInvalidByPaymentProxyCreatedSince(eq(PaymentProxy.BANCHILE), any(ZonedDateTime.class)))
            .thenThrow(new CannotGetJdbcConnectionException("el pool no entregó una conexión a tiempo"));
        var pending = mock(Transaction.class);
        when(pending.getId()).thenReturn(17);
        when(pending.getReservationId()).thenReturn(RESERVATION_ID);
        when(transactionRepository.findByStatusAndPaymentProxy(Transaction.Status.PENDING, PaymentProxy.BANCHILE))
            .thenReturn(List.of(pending));
        var reservation = mock(TicketReservation.class);
        var purchaseContext = mock(PurchaseContext.class);
        when(ticketReservationRepository.findOptionalReservationById(RESERVATION_ID)).thenReturn(Optional.of(reservation));
        when(purchaseContextManager.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(purchaseContext));
        when(ticketReservationManager.forceTransactionCheck(purchaseContext, reservation)).thenReturn(Optional.of(PaymentResult.successful("17412")));
        // después de la consulta, la transacción ya no está PENDING
        when(transactionRepository.loadOptionalByIdAndStatus(17, Transaction.Status.PENDING)).thenReturn(Optional.empty());

        int reconciled = job.reconcilePendingBanchileTransactions();

        assertEquals(1, reconciled, "la transacción PENDING se concilia aunque la revisión de las invalidadas haya fallado");
        verify(ticketReservationManager).forceTransactionCheck(purchaseContext, reservation);
    }
}
