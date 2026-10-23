import {Component, Input} from '@angular/core';
import {PaymentDetailsInfo} from '../../model/reservation-info';

/**
 * Estados de la sesión Banchile con el intento de pago fallido: los que el backend cierra como
 * FAILED (BanchilePagosWebhookManager.mapStatus). REJECTED incluye la sesión cancelada por el
 * comprador (razón ?C) y la vencida (EX).
 */
const FAILED_STATUSES = ['REJECTED', 'PARTIAL_EXPIRED'];

/** Razón de Banchile para la sesión que el comprador canceló. */
const CANCELLED_BY_THE_BUYER = '?C';

/** Razón de Banchile para la sesión que venció sin pago. */
const EXPIRED = 'EX';

const KEY_PREFIX = 'reservation-page.payment.banchile.failed-attempt.';

/** Por qué falló el intento: cada motivo tiene su texto. */
export type FailedAttemptReason = 'rejected' | 'cancelled' | 'expired';

/**
 * Aviso del resumen de la reserva cuando el último intento de pago con Banchile falló. Depende del
 * estado de la sesión y no de que falte el código de autorización: tras un rechazo de tarjeta,
 * Banchile informa el intento con autorización "000000".
 *
 * El texto sale de las traducciones (reservation-page.payment.banchile.failed-attempt.*), según el
 * motivo: rechazo del banco, cancelación del comprador (?C) o vencimiento (EX). Nunca muestra el
 * código técnico; en el rechazo del banco agrega, en texto secundario, el mensaje que informa Banchile.
 *
 * Manda el último intento de pago: con una tarjeta rechazada, Banchile deja la sesión abierta para
 * reintentar y, si el comprador vuelve al comercio, la cierra con ?C (o vence, EX). Si el último
 * intento fue rechazado, el aviso es el del rechazo del banco, con el mensaje de ese intento; solo sin
 * un intento rechazado cuenta la razón de la sesión.
 */
@Component({
  selector: 'app-banchile-failed-payment-notice',
  templateUrl: './banchile-failed-payment-notice.component.html'
})
export class BanchileFailedPaymentNoticeComponent {

  /** Detalles del último pago de la reserva (ReservationInfo.paymentDetails); null sin pago Banchile. */
  @Input()
  paymentDetails: PaymentDetailsInfo | null | undefined;

  /** true si el último intento de pago con Banchile falló: rechazado, cancelado o vencido. */
  get failed(): boolean {
    return this.paymentDetails != null && FAILED_STATUSES.includes(this.paymentDetails.status);
  }

  /** true si el banco rechazó el último intento de pago de la sesión. */
  private get attemptRejected(): boolean {
    return this.paymentDetails?.attemptStatus === 'REJECTED';
  }

  /** Motivo del intento fallido; null si el último intento no falló. */
  get reason(): FailedAttemptReason | null {
    if (!this.failed) {
      return null;
    }
    if (this.attemptRejected) {
      return 'rejected';
    }
    if (this.paymentDetails.status === 'PARTIAL_EXPIRED' || this.paymentDetails.statusReason === EXPIRED) {
      return 'expired';
    }
    return this.paymentDetails.statusReason === CANCELLED_BY_THE_BUYER ? 'cancelled' : 'rejected';
  }

  /** Clave de la traducción del aviso. */
  get textKey(): string {
    return KEY_PREFIX + this.reason;
  }

  /** Clave de la línea con el mensaje del banco. */
  get bankDetailKey(): string {
    return KEY_PREFIX + 'bank-detail';
  }

  /**
   * Mensaje que informa Banchile, solo para el rechazo del banco y sin el código: el del intento
   * rechazado, o, sin intentos, el de la sesión; null si no hay.
   */
  get bankDetail(): string | null {
    if (this.reason !== 'rejected') {
      return null;
    }
    const message = (this.attemptRejected ? this.paymentDetails.attemptMessage : this.paymentDetails.statusMessage)?.trim();
    return message ? message : null;
  }
}
