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

import alfio.manager.PurchaseContextManager;
import alfio.manager.TicketReservationManager;
import alfio.model.PurchaseContext;
import alfio.model.transaction.PaymentProxy;
import alfio.model.transaction.Transaction;
import alfio.repository.TicketReservationRepository;
import alfio.repository.TransactionRepository;
import alfio.util.ClockProvider;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Lo que hace alf.io con una notificación (webhook) de Banchile Pagos, ya interpretada por
 * {@code BanchilePaymentWebhookController}: si la firma es válida, dispara la misma verificación que
 * el cron y el poll. Nunca aplica el estado que trae la notificación: la verdad la da
 * {@code querySession}, dentro de {@link TicketReservationManager#forceTransactionCheck}, con sus
 * reglas (estado de la reserva, alertas, reembolsos, sesiones invalidadas hermanas).
 *
 * <ul>
 *   <li>La firma se valida con el secretKey ({@code BANCHILE_TRANKEY}) del nivel de configuración de
 *       la compra, el mismo que usa {@link BanchilePagosWebhookManager}. Sin firma válida, no se hace
 *       nada.</li>
 *   <li>El requestId tiene que ser el de una transacción Banchile de la reserva de la referencia:
 *       <ul>
 *         <li>la PENDING: se verifica el pago con {@code forceTransactionCheck};</li>
 *         <li>una INVALID hermana reciente (una sesión que alf.io invalidó al crear otra, y que se
 *             puede pagar igual): se revisa esa sesión como lo hace el cron
 *             ({@link BanchilePagosWebhookManager#reviewInvalidatedSession}), sin esperarlo;</li>
 *         <li>cualquier otro caso (cerrada, ya revisada, fuera de la ventana, de otra reserva): nada.</li>
 *       </ul></li>
 * </ul>
 *
 * <p>La verificación corre en segundo plano, en un executor propio de {@value #WORKERS} hilos con una
 * cola de {@value #QUEUE_CAPACITY}: Banchile no reintenta la notificación y recibe su 200 sin esperar
 * la consulta a Banchile (1 a 3 s, hasta 20 s). Cada verificación pasa por
 * {@link PaymentCheckSingleFlight}, con el mismo turno por transacción y el mismo tope global de 5
 * que el poll y el force-check: si hay otra en curso para el pago, o no queda cupo, no consulta (la
 * resuelven la que está en curso, el poll o el cron). Con 2 hilos, el webhook ocupa a lo sumo 2 de
 * esos 5 cupos. Con la cola llena, o durante el apagado, la notificación se descarta con un WARN: el
 * poll y el cron la cubren.
 */
@Component
public class BanchileWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BanchileWebhookDispatcher.class);

    /** Hilos que verifican pagos por el webhook: a lo sumo 2 de los 5 cupos del tope global. */
    public static final int WORKERS = 2;
    /** Verificaciones que pueden esperar un hilo; con la cola llena, la notificación se descarta. */
    public static final int QUEUE_CAPACITY = 20;
    /**
     * Cuánto espera el apagado a las verificaciones en curso antes de interrumpirlas. Interrumpir una
     * no deja nada a medias: la llamada a Banchile se corta y la transacción de base de datos no
     * cambia nada; el poll o el cron la repiten.
     */
    public static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private static final String COVERED_BY_POLL_AND_CRON = "la resuelven el poll o el cron";

    /**
     * Notificación de Banchile ya interpretada y validada en su forma, pero no en su firma.
     *
     * @param requestId     requestId de la sesión Banchile
     * @param reservationId ID de la reserva, reconstruido desde {@code reference}
     * @param status        {@code status.status}, tal como vino; solo se usa para la firma
     * @param date          {@code status.date}, tal como vino; solo se usa para la firma
     * @param signature     campo {@code signature}, o null si no vino
     */
    public record Notification(int requestId, String reservationId, String status, String date, String signature) {}

    /**
     * Qué se decidió con la notificación al recibirla.
     */
    public enum Outcome {
        /** La transacción PENDING del requestId: su verificación quedó en cola. */
        VERIFICATION_QUEUED(true),
        /** Una sesión INVALID reciente sin revisar: su revisión quedó en cola. */
        INVALIDATED_SESSION_REVIEW_QUEUED(true),
        /** La referencia no es de ninguna reserva. */
        UNKNOWN_RESERVATION(false),
        /** Sin firma, o la firma no calza (o la compra no tiene secretKey configurado). */
        INVALID_SIGNATURE(false),
        /** El requestId no es de ninguna transacción Banchile de la reserva. */
        UNKNOWN_REQUEST_ID(false),
        /** La transacción ya tiene un estado final: no hay nada que verificar. */
        TRANSACTION_CLOSED(false),
        /** La sesión invalidada ya se revisó en Banchile. */
        INVALIDATED_SESSION_ALREADY_REVIEWED(false),
        /** La sesión invalidada se creó antes de {@link BanchilePagosWebhookManager#INVALIDATED_SESSION_WINDOW}. */
        INVALIDATED_SESSION_OUTSIDE_WINDOW(false),
        /** Se descartó: la cola estaba llena o la aplicación se está apagando. */
        DISCARDED(false);

        private final boolean accepted;

        Outcome(boolean accepted) {
            this.accepted = accepted;
        }

        /**
         * @return true si la notificación dejó una verificación en cola
         */
        public boolean isAccepted() {
            return accepted;
        }
    }

    /**
     * Cómo terminó la verificación en segundo plano.
     */
    public enum CheckResult {
        /** Se consultó a Banchile y se aplicaron las reglas de siempre. */
        CHECKED,
        /** Había otra verificación en curso para el pago, o no quedaba cupo del tope global: no se consultó. */
        BUSY,
        /** La transacción dejó de estar PENDING (o INVALID) antes de que se la verificara: no se consultó. */
        NOT_PENDING,
        /** Se descartó sin consultar: la cola estaba llena o la aplicación se apagó. */
        DISCARDED,
        /** Falló (está en el log). */
        FAILED
    }

    /**
     * Resultado de {@link #dispatch}.
     *
     * @param outcome qué se decidió al recibir la notificación
     * @param check   cómo termina la verificación en segundo plano; si no se encoló nada, ya está
     *                completo, con {@code null} (o {@link CheckResult#DISCARDED} si se descartó)
     */
    public record Dispatch(Outcome outcome, CompletableFuture<CheckResult> check) {}

    private final PurchaseContextManager purchaseContextManager;
    private final TicketReservationManager ticketReservationManager;
    private final TicketReservationRepository ticketReservationRepository;
    private final TransactionRepository transactionRepository;
    private final BanchilePagosWebhookManager banchilePagosWebhookManager;
    private final PaymentCheckSingleFlight paymentCheckSingleFlight;
    private final TransactionTemplate transactionTemplate;
    private final ClockProvider clockProvider;
    private final ThreadPoolExecutor executor;
    private final int queueCapacity;
    private final Duration shutdownWait;

    /**
     * Dispatcher con el executor de producción: {@value #WORKERS} hilos, cola de
     * {@value #QUEUE_CAPACITY} y {@link #SHUTDOWN_WAIT} de espera al apagar.
     */
    @Autowired
    public BanchileWebhookDispatcher(PurchaseContextManager purchaseContextManager,
                                     TicketReservationManager ticketReservationManager,
                                     TicketReservationRepository ticketReservationRepository,
                                     TransactionRepository transactionRepository,
                                     BanchilePagosWebhookManager banchilePagosWebhookManager,
                                     PaymentCheckSingleFlight paymentCheckSingleFlight,
                                     PlatformTransactionManager transactionManager,
                                     ClockProvider clockProvider) {
        this(purchaseContextManager, ticketReservationManager, ticketReservationRepository, transactionRepository,
            banchilePagosWebhookManager, paymentCheckSingleFlight, transactionManager, clockProvider,
            WORKERS, QUEUE_CAPACITY, SHUTDOWN_WAIT);
    }

    /**
     * Dispatcher con un executor del tamaño dado.
     *
     * @param workers       hilos que verifican en paralelo
     * @param queueCapacity verificaciones que pueden esperar un hilo
     * @param shutdownWait  cuánto espera el apagado a las verificaciones en curso antes de interrumpirlas
     */
    public BanchileWebhookDispatcher(PurchaseContextManager purchaseContextManager,
                                     TicketReservationManager ticketReservationManager,
                                     TicketReservationRepository ticketReservationRepository,
                                     TransactionRepository transactionRepository,
                                     BanchilePagosWebhookManager banchilePagosWebhookManager,
                                     PaymentCheckSingleFlight paymentCheckSingleFlight,
                                     PlatformTransactionManager transactionManager,
                                     ClockProvider clockProvider,
                                     int workers,
                                     int queueCapacity,
                                     Duration shutdownWait) {
        this.purchaseContextManager = purchaseContextManager;
        this.ticketReservationManager = ticketReservationManager;
        this.ticketReservationRepository = ticketReservationRepository;
        this.transactionRepository = transactionRepository;
        this.banchilePagosWebhookManager = banchilePagosWebhookManager;
        this.paymentCheckSingleFlight = paymentCheckSingleFlight;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clockProvider = clockProvider;
        this.queueCapacity = queueCapacity;
        this.shutdownWait = Objects.requireNonNull(shutdownWait);
        this.executor = new ThreadPoolExecutor(workers, workers, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queueCapacity),
            new BasicThreadFactory.Builder().namingPattern("banchile-webhook-%d").daemon(true).build());
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * Decide qué hacer con la notificación y, si corresponde, deja en cola la verificación. No espera
     * a Banchile: solo lee la base de datos.
     *
     * @param notification notificación ya interpretada
     * @return qué se decidió y cómo termina la verificación en segundo plano
     */
    public Dispatch dispatch(Notification notification) {
        String reservationId = notification.reservationId();
        String requestId = String.valueOf(notification.requestId());
        var optionalPurchaseContext = purchaseContextManager.findByReservationId(reservationId);
        if (optionalPurchaseContext.isEmpty()) {
            log.warn("[banchile-webhook] notificación de una reserva desconocida: requestId={} reserva={}. No se dispara ninguna verificación",
                requestId, reservationId);
            return ignored(Outcome.UNKNOWN_RESERVATION);
        }
        var purchaseContext = optionalPurchaseContext.get();
        String secretKey = banchilePagosWebhookManager.getWebhookSignatureKey(purchaseContext.getConfigurationLevel());
        if (StringUtils.isBlank(secretKey)) {
            log.warn("[banchile-webhook] la compra de la reserva {} no tiene BANCHILE_TRANKEY: no se puede validar la firma de requestId={}. "
                + "No se dispara ninguna verificación", reservationId, requestId);
            return ignored(Outcome.INVALID_SIGNATURE);
        }
        var verification = BanchileWebhookSignature.verify(requestId, notification.status(), notification.date(),
            notification.signature(), secretKey);
        if (!verification.valid()) {
            log.warn("[banchile-webhook] firma ausente o inválida: requestId={} reserva={} ({}). No se dispara ninguna verificación",
                requestId, reservationId, verification.diagnostic());
            return ignored(Outcome.INVALID_SIGNATURE);
        }

        var optionalTransaction = transactionRepository.findAllByReservationIdAndPaymentProxy(reservationId, PaymentProxy.BANCHILE).stream()
            .filter(tx -> requestId.equals(tx.getPaymentId()))
            .findFirst();
        if (optionalTransaction.isEmpty()) {
            log.warn("[banchile-webhook] requestId={} no es de ninguna transacción Banchile de la reserva {}. No se dispara ninguna verificación",
                requestId, reservationId);
            return ignored(Outcome.UNKNOWN_REQUEST_ID);
        }
        var transaction = optionalTransaction.get();
        String description = "la verificación del requestId=" + requestId + " de la reserva " + reservationId;
        return switch (transaction.getStatus()) {
            case PENDING -> {
                log.info("[banchile-webhook] firma válida ({}): se verifica el pago requestId={} de la reserva {}",
                    verification.diagnostic(), requestId, reservationId);
                yield queue(Outcome.VERIFICATION_QUEUED, description,
                    () -> verifyPayment(transaction.getId(), reservationId, requestId));
            }
            case INVALID -> invalidatedSession(transaction, purchaseContext, requestId, verification.diagnostic());
            default -> {
                log.info("[banchile-webhook] la transacción requestId={} de la reserva {} ya está {}: no hay nada que verificar",
                    requestId, reservationId, transaction.getStatus());
                yield ignored(Outcome.TRANSACTION_CLOSED);
            }
        };
    }

    /**
     * Notificación de una sesión que alf.io dejó INVALID: dentro de la ventana del cron y sin revisar,
     * se revisa como lo hace el cron.
     */
    private Dispatch invalidatedSession(Transaction transaction, PurchaseContext purchaseContext, String requestId, String signatureDiagnostic) {
        String reservationId = transaction.getReservationId();
        if (BanchilePagosWebhookManager.isInvalidatedSessionReviewed(transaction)) {
            log.info("[banchile-webhook] la sesión invalidada requestId={} de la reserva {} ya se revisó en Banchile ({}): no se vuelve a consultar",
                requestId, reservationId, transaction.getMetadata().get(BanchilePagosWebhookManager.INVALIDATED_SESSION_REVIEW));
            return ignored(Outcome.INVALIDATED_SESSION_ALREADY_REVIEWED);
        }
        var createdSince = ZonedDateTime.now(clockProvider.getClock()).minus(BanchilePagosWebhookManager.INVALIDATED_SESSION_WINDOW);
        if (transaction.getTimestamp().isBefore(createdSince)) {
            log.warn("[banchile-webhook] notificación de la sesión invalidada requestId={} de la reserva {}, creada hace más de {} minutos: "
                    + "fuera de la ventana de revisión, alf.io no la consulta. Revisarla en el portal de Banchile",
                requestId, reservationId, BanchilePagosWebhookManager.INVALIDATED_SESSION_WINDOW.toMinutes());
            return ignored(Outcome.INVALIDATED_SESSION_OUTSIDE_WINDOW);
        }
        log.info("[banchile-webhook] firma válida ({}): se revisa la sesión invalidada requestId={} de la reserva {}",
            signatureDiagnostic, requestId, reservationId);
        return queue(Outcome.INVALIDATED_SESSION_REVIEW_QUEUED,
            "la revisión de la sesión invalidada requestId=" + requestId + " de la reserva " + reservationId,
            () -> reviewInvalidatedSession(transaction.getId(), purchaseContext, requestId, reservationId));
    }

    /**
     * Verifica el pago como el poll: con el turno del pago y un cupo del tope global, y solo si la
     * transacción de la notificación sigue siendo la PENDING de la reserva.
     */
    private CheckResult verifyPayment(int transactionId, String reservationId, String requestId) {
        return paymentCheckSingleFlight.callIfIdle(transactionId, () -> {
            boolean stillPending = transactionRepository.loadOptionalByReservationIdAndStatus(reservationId, Transaction.Status.PENDING)
                .filter(pending -> pending.getId() == transactionId)
                .isPresent();
            var reservation = ticketReservationRepository.findOptionalReservationById(reservationId);
            var purchaseContext = purchaseContextManager.findByReservationId(reservationId);
            if (!stillPending || reservation.isEmpty() || purchaseContext.isEmpty()) {
                log.info("[banchile-webhook] requestId={} de la reserva {} ya no está PENDING: no se consulta", requestId, reservationId);
                return CheckResult.NOT_PENDING;
            }
            var result = ticketReservationManager.forceTransactionCheck(purchaseContext.get(), reservation.get());
            log.info("[banchile-webhook] verificación del requestId={} de la reserva {}: {}", requestId, reservationId,
                result.map(Object::toString).orElse("sin transacción PENDING"));
            return CheckResult.CHECKED;
        }, () -> busy(requestId, reservationId));
    }

    /**
     * Revisa la sesión invalidada como el cron ({@code BanchileReconciliationJob}): en su propia
     * transacción de base de datos, con el lock de la fila, y con el turno de la transacción y un
     * cupo del tope global, porque también consulta a Banchile con una conexión tomada.
     */
    private CheckResult reviewInvalidatedSession(int transactionId, PurchaseContext purchaseContext, String requestId, String reservationId) {
        return paymentCheckSingleFlight.callIfIdle(transactionId, () -> {
            try {
                var review = transactionTemplate.execute(status -> transactionRepository
                    .loadOptionalByIdAndStatusForUpdate(transactionId, Transaction.Status.INVALID)
                    .map(locked -> banchilePagosWebhookManager.reviewInvalidatedSession(locked, purchaseContext, false))
                    .orElse(null));
                if (review == null) {
                    log.info("[banchile-webhook] la sesión requestId={} de la reserva {} ya no está INVALID: no se consulta", requestId, reservationId);
                    return CheckResult.NOT_PENDING;
                }
                log.info("[banchile-webhook] sesión invalidada requestId={} de la reserva {}: {}", requestId, reservationId, review);
                return CheckResult.CHECKED;
            } catch (BanchilePagosClient.BanchilePagosException e) {
                log.warn("[banchile-webhook] Banchile no respondió al revisar la sesión invalidada requestId={} de la reserva {}: {}. "
                    + "La vuelve a revisar el cron", requestId, reservationId, e.getMessage());
                return CheckResult.FAILED;
            }
        }, () -> busy(requestId, reservationId));
    }

    private static CheckResult busy(String requestId, String reservationId) {
        log.info("[banchile-webhook] requestId={} de la reserva {}: ya hay una verificación en curso para el pago, o no queda cupo de las "
            + "verificaciones simultáneas; no se consulta (la resuelven la que está en curso, el poll o el cron)", requestId, reservationId);
        return CheckResult.BUSY;
    }

    private Dispatch queue(Outcome outcome, String description, Supplier<CheckResult> check) {
        var task = new QueuedCheck(description, check);
        try {
            executor.execute(task);
            return new Dispatch(outcome, task.result);
        } catch (RejectedExecutionException e) {
            log.warn("[banchile-webhook] se descarta {}: {}; {}", description,
                executor.isShutdown() ? "la aplicación se está apagando" : "la cola de " + queueCapacity + " verificaciones está llena",
                COVERED_BY_POLL_AND_CRON);
            return new Dispatch(Outcome.DISCARDED, CompletableFuture.completedFuture(CheckResult.DISCARDED));
        }
    }

    private static Dispatch ignored(Outcome outcome) {
        return new Dispatch(outcome, CompletableFuture.completedFuture(null));
    }

    /**
     * Al apagar la aplicación: no acepta más notificaciones, descarta con un WARN las verificaciones
     * en cola, espera a lo sumo {@code shutdownWait} a las que están en curso y, si no terminan, las
     * interrumpe. Spring lo llama antes de cerrar el pool de conexiones, del que depende este bean.
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        var queued = new ArrayList<Runnable>();
        executor.getQueue().drainTo(queued);
        for (var task : queued) {
            var check = (QueuedCheck) task;
            log.warn("[banchile-webhook] la aplicación se apaga: se descarta {}, que estaba en cola; {}", check.description, COVERED_BY_POLL_AND_CRON);
            check.result.complete(CheckResult.DISCARDED);
        }
        try {
            if (!executor.awaitTermination(shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("[banchile-webhook] las verificaciones en curso no terminaron en {} ms: se interrumpen; {}", shutdownWait.toMillis(),
                    COVERED_BY_POLL_AND_CRON);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("[banchile-webhook] el apagado se interrumpió mientras esperaba las verificaciones en curso: se interrumpen");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Verificación en cola, con su resultado: también se completa si se descarta al apagar.
     */
    private static final class QueuedCheck implements Runnable {
        private final String description;
        private final Supplier<CheckResult> check;
        private final CompletableFuture<CheckResult> result = new CompletableFuture<>();

        private QueuedCheck(String description, Supplier<CheckResult> check) {
            this.description = description;
            this.check = check;
        }

        @Override
        public void run() {
            try {
                result.complete(check.get());
            } catch (RuntimeException e) {
                log.error("[banchile-webhook] falló {}; {}", description, COVERED_BY_POLL_AND_CRON, e);
                result.complete(CheckResult.FAILED);
            }
        }
    }
}
