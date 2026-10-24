import {ComponentFixture, fakeAsync, TestBed, tick} from '@angular/core/testing';
import {ActivatedRoute, Router} from '@angular/router';
import {UntypedFormBuilder} from '@angular/forms';
import {TranslateModule} from '@ngx-translate/core';
import {NgbModal} from '@ng-bootstrap/ng-bootstrap';
import {defer, of, Subject, throwError} from 'rxjs';
import {OverviewComponent} from './overview.component';
import {ReservationService} from '../../shared/reservation.service';
import {I18nService} from '../../shared/i18n.service';
import {AnalyticsService} from '../../shared/analytics.service';
import {PurchaseContextService} from '../../shared/purchase-context.service';
import {FeedbackService} from '../../shared/feedback/feedback.service';
import {ReservationInfo, ReservationStatus, ReservationStatusInfo} from '../../model/reservation-info';

/**
 * Volver con "Atrás" desde Banchile: el navegador restaura el resumen desde su caché (bfcache), tal
 * como quedó al irse, con "Por favor, espere" y sin hacer ninguna petición.
 */
describe('OverviewComponent al volver desde la página de pago', () => {
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
    router.navigate.and.returnValue(Promise.resolve(true));
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

  function backFromThePaymentPage(persisted = true): void {
    window.dispatchEvent(new PageTransitionEvent('pageshow', {persisted}));
  }

  it('pasa a "procesando pago" si el pago sigue en curso, sin perder el Adelante a Banchile y sin que vuelva "Pagar"', () => {
    component.submitting = true;
    reservationStatusIs('EXTERNAL_PROCESSING_PAYMENT');

    backFromThePaymentPage();

    expect(reservationService.getReservationStatusInfo).toHaveBeenCalledWith('r1');
    expect(router.navigate).toHaveBeenCalledWith(['event', 'bingo', 'reservation', 'r1', 'processing-payment'], {replaceUrl: true});
    expect(component.submitting).toBeTrue();
  });

  it('también con la reserva esperando la confirmación externa', () => {
    component.submitting = true;
    reservationStatusIs('WAITING_EXTERNAL_CONFIRMATION');

    backFromThePaymentPage();

    expect(router.navigate).toHaveBeenCalledWith(['event', 'bingo', 'reservation', 'r1', 'processing-payment'], {replaceUrl: true});
  });

  it('si el pago ya falló y la reserva volvió a PENDING, se queda en el resumen y lo recarga (con el aviso)', () => {
    component.submitting = true;
    reservationStatusIs('PENDING');
    reservationService.getReservationInfo.calls.reset();

    backFromThePaymentPage();

    expect(component.submitting).toBeFalse();
    expect(reservationService.getReservationInfo).toHaveBeenCalledOnceWith('r1');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('con otro estado (por ejemplo, el pago confirmado) deja que el guard elija la página', () => {
    component.submitting = true;
    reservationStatusIs('COMPLETE');

    backFromThePaymentPage();

    expect(router.navigate).toHaveBeenCalledWith(['event', 'bingo', 'reservation', 'r1', 'success'], {replaceUrl: true});
    expect(component.submitting).toBeTrue();
  });

  it('quita el aviso de "salir de la página" que se pone al pagar', () => {
    const removeEventListener = spyOn(window, 'removeEventListener').and.callThrough();
    component.submitting = true;
    reservationStatusIs('EXTERNAL_PROCESSING_PAYMENT');

    backFromThePaymentPage();

    expect(removeEventListener).toHaveBeenCalledWith('beforeunload', jasmine.any(Function));
  });

  it('sigue esperando mientras consulta el estado, y "Pagar" vuelve solo si la reserva está PENDING', () => {
    const answer = new Subject<ReservationStatusInfo>();
    reservationService.getReservationStatusInfo.and.returnValue(answer);
    component.submitting = true;

    backFromThePaymentPage();
    expect(component.submitting).toBeTrue();

    answer.next({status: 'PENDING', validatedBookingInformation: true});
    expect(component.submitting).toBeFalse();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('si la consulta del estado falla una vez, la reintenta y sigue con la respuesta', fakeAsync(() => {
    const reload = spyOn(component, 'reloadPage');
    let queries = 0;
    reservationService.getReservationStatusInfo.and.returnValue(defer(() => ++queries === 1
      ? throwError(() => new Error('sin conexión'))
      : of<ReservationStatusInfo>({status: 'EXTERNAL_PROCESSING_PAYMENT', validatedBookingInformation: true})));
    component.submitting = true;

    backFromThePaymentPage();
    expect(router.navigate).not.toHaveBeenCalled();
    tick(OverviewComponent.STATUS_RETRY_DELAY_MS);

    expect(queries).toBe(2);
    expect(router.navigate).toHaveBeenCalledWith(['event', 'bingo', 'reservation', 'r1', 'processing-payment'], {replaceUrl: true});
    expect(reload).not.toHaveBeenCalled();
    expect(component.submitting).toBeTrue();
  }));

  it('si la consulta del estado sigue fallando, recarga la página en vez de mostrar "Pagar"', fakeAsync(() => {
    const reload = spyOn(component, 'reloadPage');
    reservationService.getReservationStatusInfo.and.returnValue(throwError(() => new Error('sin conexión')));
    component.submitting = true;

    backFromThePaymentPage();
    tick(OverviewComponent.STATUS_RETRY_DELAY_MS * OverviewComponent.STATUS_RETRIES);

    expect(reload).toHaveBeenCalledTimes(1);
    expect(component.submitting).toBeTrue();
    expect(router.navigate).not.toHaveBeenCalled();
  }));

  it('si se sale de la página mientras reintenta, no recarga después', fakeAsync(() => {
    const reload = spyOn(component, 'reloadPage');
    reservationService.getReservationStatusInfo.and.returnValue(throwError(() => new Error('sin conexión')));

    backFromThePaymentPage();
    fixture.destroy();
    tick(OverviewComponent.STATUS_RETRY_DELAY_MS * OverviewComponent.STATUS_RETRIES);

    expect(reload).not.toHaveBeenCalled();
  }));

  it('una carga normal de la página no hace nada', () => {
    component.submitting = true;
    reservationStatusIs('EXTERNAL_PROCESSING_PAYMENT');

    backFromThePaymentPage(false);

    expect(component.submitting).toBeTrue();
    expect(reservationService.getReservationStatusInfo).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });
});
