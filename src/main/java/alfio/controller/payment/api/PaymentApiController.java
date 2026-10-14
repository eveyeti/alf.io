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
package alfio.controller.payment.api;

import alfio.manager.PaymentManager;
import alfio.manager.PurchaseContextManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.payment.PaymentCheckSingleFlight;
import alfio.manager.support.PaymentResult;
import alfio.model.PurchaseContext;
import alfio.model.TicketReservation;
import alfio.model.transaction.PaymentMethod;
import alfio.model.transaction.Transaction;
import alfio.model.transaction.TransactionInitializationToken;
import alfio.repository.TransactionRepository;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@AllArgsConstructor
public class PaymentApiController {

    private final PaymentManager paymentManager;
    private final TicketReservationManager ticketReservationManager;
    private final PurchaseContextManager purchaseContextManager;
    private final TransactionRepository transactionRepository;
    private final PaymentCheckSingleFlight paymentCheckSingleFlight;

    @PostMapping({"/api/reservation/{reservationId}/payment/{method}/init",
        "/api/events/{eventName}/reservation/{reservationId}/payment/{method}/init" //<-deprecated
    })
    public ResponseEntity<TransactionInitializationToken> initTransaction(@PathVariable String reservationId,
                                                                          @PathVariable("method") String paymentMethodStr,
                                                                          @RequestParam MultiValueMap<String, String> allParams) {

        var paymentMethod = PaymentMethod.safeParse(paymentMethodStr);
        if (paymentMethod == null) {
            return ResponseEntity.badRequest().build();
        }

        return getEventReservationPair(reservationId)
            .flatMap(pair -> ticketReservationManager.initTransaction(pair.getLeft(), reservationId, paymentMethod, allParams))
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Optional<Pair<? extends PurchaseContext, TicketReservation>> getEventReservationPair(String reservationId) {
        return purchaseContextManager.findByReservationId(reservationId)
            .map(event -> Pair.of(event, ticketReservationManager.findById(reservationId)))
            .filter(pair -> pair.getRight().isPresent())
            .map(pair -> Pair.of(pair.getLeft(), pair.getRight().orElseThrow()));
    }

    @GetMapping({
        "/api/reservation/{reservationId}/payment/{method}/status",
        "/api/events/{eventName}/reservation/{reservationId}/payment/{method}/status" //<-deprecated
    })
    public ResponseEntity<PaymentResult> getTransactionStatus(@PathVariable String reservationId,
                                                              @PathVariable("method") String paymentMethodStr) {
        var paymentMethod = PaymentMethod.safeParse(paymentMethodStr);
        if (paymentMethod == null) {
            return ResponseEntity.badRequest().build();
        }

        return getEventReservationPair(reservationId)
            .flatMap(pair -> paymentManager.getTransactionStatus(pair.getRight(), paymentMethod))
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping({
        "/api/v2/public/reservation/{reservationId}/transaction/force-check",
        "/api/v2/public/event/{eventName}/reservation/{reservationId}/transaction/force-check" //<-deprecated
    })
    public ResponseEntity<PaymentResult> forceCheckStatus(@PathVariable String reservationId) {
        var pendingTransaction = transactionRepository.loadOptionalByReservationIdAndStatus(reservationId, Transaction.Status.PENDING);
        if (pendingTransaction.isEmpty()) {
            // sin transacción PENDING no hay nada que verificar: 404, el mismo resultado que da forceTransactionCheck
            return ResponseEntity.notFound().build();
        }
        // una sola verificación por pago a la vez, y a lo sumo 5 en total, compartidas con el polling
        // de /status: con otra en curso para el pago, o sin cupo, se responde "pendiente" sin esperar
        // el lock de la transacción ni tomar una conexión
        var transaction = pendingTransaction.get();
        return ResponseEntity.of(paymentCheckSingleFlight.callIfIdle(transaction.getId(),
            () -> getEventReservationPair(reservationId)
                .flatMap(pair -> ticketReservationManager.forceTransactionCheck(pair.getLeft(), pair.getRight())),
            () -> Optional.of(PaymentResult.pending(transaction.getPaymentId()))));
    }
}
