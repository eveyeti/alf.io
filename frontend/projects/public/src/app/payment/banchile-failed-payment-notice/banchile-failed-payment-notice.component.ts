import {Component, Input} from '@angular/core';
import {PaymentDetailsInfo} from '../../model/reservation-info';

/**
 * Estados de la sesión Banchile con el intento de pago fallido: los que el backend cierra como
 * FAILED (BanchilePagosWebhookManager.mapStatus). REJECTED incluye la sesión cancelada por el
 * comprador (razón ?C) y la vencida (EX).
 */
const FAILED_STATUSES = ['REJECTED', 'PARTIAL_EXPIRED'];

/**
 * Aviso del resumen de la reserva cuando el último intento de pago con Banchile falló. Depende del
 * estado de la sesión y no de que falte el código de autorización: tras un rechazo de tarjeta,
 * Banchile informa el intento con autorización "000000".
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
}
