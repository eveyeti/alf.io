import {Component, OnDestroy, OnInit} from '@angular/core';
import {ReservationInfo} from '../../model/reservation-info';
import {ActivatedRoute, Router} from '@angular/router';
import {ReservationService} from '../../shared/reservation.service';
import {zip} from 'rxjs';
import {I18nService} from '../../shared/i18n.service';
import {AnalyticsService} from '../../shared/analytics.service';
import {PurchaseContextService, PurchaseContextType} from '../../shared/purchase-context.service';
import {PurchaseContext} from '../../model/purchase-context';
import {SearchParams} from '../../model/search-params';
import {notifyPaymentErrorToParent} from '../../shared/util';

/**
 * Cuánto espera "procesando pago" antes de explicar qué hacer si el resultado del pago con Banchile
 * no llega: terminar de pagar en Banchile, o cerrar la página sin perder la reserva.
 */
export const BANCHILE_HELP_DELAY_MS = 60_000;

@Component({
  selector: 'app-processing-payment',
  templateUrl: './processing-payment.component.html'
})
export class ProcessingPaymentComponent implements OnInit, OnDestroy {

  reservationInfo: ReservationInfo;
  purchaseContext: PurchaseContext;

  private purchaseContextType: PurchaseContextType;
  private publicIdentifier: string;
  private reservationId: string;
  forceCheckVisible = false;
  providerWarningVisible = false;
  private forceCheckInProgress = false;
  private banchileHelpDelayElapsed = false;

  private intervalId: any;
  private banchileHelpTimeoutId: any;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private reservationService: ReservationService,
    private purchaseContextService: PurchaseContextService,
    private i18nService: I18nService,
    private analytics: AnalyticsService
    ) { }

  public ngOnInit(): void {
    zip(this.route.data, this.route.params).subscribe(([data, params]) => {
      this.purchaseContextType = data.type;
      this.publicIdentifier = params[data.publicIdentifierParameter];
      this.reservationId = params['reservationId'];

      zip(
        this.purchaseContextService.getContext(this.purchaseContextType, this.publicIdentifier),
        this.reservationService.getReservationInfo(this.reservationId)
      ).subscribe(([ev, reservationInfo]) => {
        this.purchaseContext = ev;
        this.reservationInfo = reservationInfo;
        this.i18nService.setPageTitle('show-ticket.header.title', ev);
        this.analytics.pageView(ev.analyticsConfiguration);
      });

      let checkCount = 0;
      this.intervalId = setInterval(() => {
        if (this.reservationInfo == null) {
          // la reserva todavía no cargó: sin su estado no hay con qué comparar, se consulta en el próximo tick
          return;
        }
        const currentStatus = this.reservationInfo.status;
        this.reservationService.getReservationStatusInfo(this.reservationId).subscribe(res => {
          checkCount++;
          if (res.status !== currentStatus) {
            clearInterval(this.intervalId);
            this.reservationStateChanged();
          }
          if ((!this.forceCheckVisible || checkCount > 120) && !this.forceCheckInProgress && checkCount % 10 === 0) {
            // con Banchile, a los 4 minutos lo normal es que el comprador no haya terminado de pagar, no
            // que el proveedor falle: la explicación de Banchile reemplaza a ese aviso
            this.providerWarningVisible = checkCount > 120 && !this.paidWithBanchile;
            this.forceCheckVisible = !this.providerWarningVisible;
          }
        });
      }, 2000);
      this.banchileHelpTimeoutId = setTimeout(() => this.banchileHelpDelayElapsed = true, BANCHILE_HELP_DELAY_MS);
    });
  }

  /** true si la reserva está esperando un pago con Banchile. */
  get paidWithBanchile(): boolean {
    return this.reservationInfo?.paymentProxy === 'BANCHILE';
  }

  /**
   * true si hay que explicar qué hacer mientras no llega el resultado del pago con Banchile: pasó un
   * minuto sin resultado. El comprador puede haber vuelto con "Atrás" o no haber terminado de pagar;
   * la página sigue consultando el estado y cambia sola si llega el resultado.
   */
  get banchileHelpVisible(): boolean {
    return this.banchileHelpDelayElapsed && this.paidWithBanchile;
  }
  private reservationStateChanged() {
    // try to navigate to /success. If the reservation is in a different status, the user will be
    // redirected accordingly.
    this.router.navigate([this.purchaseContextType, this.publicIdentifier, 'reservation', this.reservationId, 'success'], {
      queryParams: SearchParams.transformParams(this.route.snapshot.queryParams, this.route.snapshot.queryParams)
    });
  }

  public ngOnDestroy() {
    clearInterval(this.intervalId);
    clearTimeout(this.banchileHelpTimeoutId);
  }

  forceCheck(): void {
    this.forceCheckVisible = false;
    this.forceCheckInProgress = true;
    this.reservationService.forcePaymentStatusCheck(this.reservationId).subscribe(status => {
      if (status.redirect) {
        window.location.href = status.redirectUrl;
      } else if (status.success || status.failure) {
        this.reservationStateChanged();
      }
      this.forceCheckInProgress = false;
    }, err => {
      console.log('got error', err);
      notifyPaymentErrorToParent(this.purchaseContext, this.reservationInfo, this.reservationId, err);
    });
  }


}
