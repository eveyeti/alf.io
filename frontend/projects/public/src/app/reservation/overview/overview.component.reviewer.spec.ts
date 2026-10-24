import {ComponentFixture, fakeAsync, TestBed, tick} from '@angular/core/testing';
import {ActivatedRoute, Router} from '@angular/router';
import {UntypedFormBuilder} from '@angular/forms';
import {TranslateModule} from '@ngx-translate/core';
import {NgbModal} from '@ng-bootstrap/ng-bootstrap';
import {defer, of, throwError} from 'rxjs';
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
   * La carrera que encontró la revisión: con el pago en curso, el resumen dejaba de esperar (volvía la
   * fila del botón "Pagar") antes de que terminara la navegación a "procesando pago". Un clic en ese
   * momento llamaba a confirmOverview, que crea otra sesión en Banchile y deja INVALID la que el
   * comprador estaba pagando. Ahora sigue en "Por favor, espere" hasta que cambia la página.
   */
  it('con la navegación a "procesando pago" pendiente, el botón "Pagar" no vuelve', () => {
    component.submitting = true;
    reservationStatusIs('EXTERNAL_PROCESSING_PAYMENT');

    backFromThePaymentPage();

    expect(router.navigate).toHaveBeenCalledWith(['event', 'bingo', 'reservation', 'r1', 'processing-payment'], {replaceUrl: true});
    expect(component.submitting).toBeTrue();
  });

  /**
   * Lo mismo si falla la consulta del estado: no se sabe si hay un pago en curso, así que no vuelve
   * "Pagar". Reintenta y, si sigue fallando, recarga la página: el guard consulta el estado de nuevo y
   * solo muestra el resumen, con "Pagar", si la reserva está PENDING.
   */
  it('si falla la consulta del estado, el botón "Pagar" no vuelve: reintenta y después recarga la página', fakeAsync(() => {
    const reload = spyOn(component, 'reloadPage');
    let queries = 0;
    reservationService.getReservationStatusInfo.and.returnValue(defer(() => {
      queries++;
      return throwError(() => new Error('sin red'));
    }));
    component.submitting = true;

    backFromThePaymentPage();
    tick(OverviewComponent.STATUS_RETRY_DELAY_MS * OverviewComponent.STATUS_RETRIES);

    expect(queries).toBe(1 + OverviewComponent.STATUS_RETRIES);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(router.navigate).not.toHaveBeenCalled();
    expect(component.submitting).toBeTrue();
  }));

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
