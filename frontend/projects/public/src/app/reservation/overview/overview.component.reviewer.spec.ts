import {ComponentFixture, TestBed} from '@angular/core/testing';
import {ActivatedRoute, Router} from '@angular/router';
import {UntypedFormBuilder} from '@angular/forms';
import {TranslateModule} from '@ngx-translate/core';
import {NgbModal} from '@ng-bootstrap/ng-bootstrap';
import {of, throwError} from 'rxjs';
import {OverviewComponent} from './overview.component';
import {ReservationService} from '../../shared/reservation.service';
import {I18nService} from '../../shared/i18n.service';
import {AnalyticsService} from '../../shared/analytics.service';
import {PurchaseContextService} from '../../shared/purchase-context.service';
import {FeedbackService} from '../../shared/feedback/feedback.service';
import {ReservationInfo, ReservationStatus} from '../../model/reservation-info';

/**
 * Revisor (v6): volver con "Atrás" desde Banchile (bfcache).
 *
 * En la app, router.navigate no termina al instante: el ReservationGuard de "procesando pago" consulta
 * el estado por HTTP antes de activar la ruta. Mientras tanto el resumen sigue en pantalla, y la fila
 * del botón "Pagar" se muestra con !submitting (overview.component.html:121).
 */
describe('OverviewComponent al volver desde la página de pago (revisor v6)', () => {
  let fixture: ComponentFixture<OverviewComponent>;
  let component: OverviewComponent;
  let reservationService: jasmine.SpyObj<ReservationService>;
  let router: jasmine.SpyObj<Router>;

  const reservationInfo = {
    id: 'r1',
    activePaymentMethods: {CREDIT_CARD: {paymentProxy: 'BANCHILE', parameters: {}}},
    orderSummary: {free: false},
    tokenAcquired: false
  } as unknown as ReservationInfo;

  beforeEach(async () => {
    reservationService = jasmine.createSpyObj<ReservationService>('ReservationService',
      ['getReservationInfo', 'getReservationStatusInfo']);
    reservationService.getReservationInfo.and.returnValue(of(reservationInfo));
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    // la navegación queda pendiente, como mientras el guard espera la respuesta del estado
    router.navigate.and.returnValue(new Promise<boolean>(() => {}));
    const purchaseContextService = jasmine.createSpyObj<PurchaseContextService>('PurchaseContextService', ['getContext']);
    purchaseContextService.getContext.and.returnValue(of({analyticsConfiguration: {}} as any));

    await TestBed.configureTestingModule({
      declarations: [OverviewComponent],
      imports: [TranslateModule.forRoot()],
      providers: [
        UntypedFormBuilder,
        {provide: ActivatedRoute, useValue: {
          data: of({type: 'event', publicIdentifierParameter: 'eventShortName'}),
          params: of({eventShortName: 'bingo', reservationId: 'r1'}),
          snapshot: {queryParams: {}, params: {}}
        }},
        {provide: Router, useValue: router},
        {provide: ReservationService, useValue: reservationService},
        {provide: I18nService, useValue: jasmine.createSpyObj('I18nService', ['setPageTitle'])},
        {provide: AnalyticsService, useValue: jasmine.createSpyObj('AnalyticsService', ['pageView'])},
        {provide: NgbModal, useValue: jasmine.createSpyObj('NgbModal', ['open'])},
        {provide: PurchaseContextService, useValue: purchaseContextService},
        {provide: FeedbackService, useValue: jasmine.createSpyObj('FeedbackService', ['showError', 'showInfo', 'showSuccess'])}
      ]
    }).overrideTemplate(OverviewComponent, '').compileComponents();

    fixture = TestBed.createComponent(OverviewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  function reservationStatusIs(status: ReservationStatus, validatedBookingInformation = true): void {
    reservationService.getReservationStatusInfo.and.returnValue(of({status, validatedBookingInformation}));
  }

  function backFromThePaymentPage(): void {
    window.dispatchEvent(new PageTransitionEvent('pageshow', {persisted: true}));
  }

  /**
   * Documenta la carrera: con el pago en curso, el resumen deja de esperar (vuelve la fila del botón
   * "Pagar") antes de que termine la navegación a "procesando pago". Un clic en ese momento llama a
   * confirmOverview, que crea otra sesión en Banchile y deja INVALID la que el comprador estaba pagando.
   */
  it('documenta: con la navegación a "procesando pago" pendiente, el botón "Pagar" ya volvió', () => {
    component.submitting = true;
    reservationStatusIs('EXTERNAL_PROCESSING_PAYMENT');

    backFromThePaymentPage();

    expect(router.navigate).toHaveBeenCalledWith(['event', 'bingo', 'reservation', 'r1', 'processing-payment'], {replaceUrl: true});
    expect(component.submitting).toBeFalse();
  });

  /** Lo mismo si falla la consulta del estado: el resumen viejo queda con "Pagar" aunque el pago siga en curso. */
  it('documenta: si falla la consulta del estado, el botón "Pagar" vuelve sin saber si hay un pago en curso', () => {
    component.submitting = true;
    reservationService.getReservationStatusInfo.and.returnValue(throwError(() => new Error('sin red')));

    backFromThePaymentPage();

    expect(router.navigate).not.toHaveBeenCalled();
    expect(component.submitting).toBeFalse();
  });

  /** PENDING sin los datos de la reserva validados: no se queda en el resumen (mata la mutación F09). */
  it('con la reserva en PENDING pero sin los datos validados, deja que el guard elija la página', () => {
    component.submitting = true;
    reservationStatusIs('PENDING', false);
    reservationService.getReservationInfo.calls.reset();

    backFromThePaymentPage();

    expect(reservationService.getReservationInfo).not.toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['event', 'bingo', 'reservation', 'r1', 'success'], {replaceUrl: true});
  });
});
