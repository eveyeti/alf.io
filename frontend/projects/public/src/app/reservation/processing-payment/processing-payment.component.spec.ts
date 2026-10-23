import {ComponentFixture, fakeAsync, TestBed, tick} from '@angular/core/testing';
import {NO_ERRORS_SCHEMA} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {TranslateModule} from '@ngx-translate/core';
import {of} from 'rxjs';
import {ProcessingPaymentComponent} from './processing-payment.component';
import {ReservationService} from '../../shared/reservation.service';
import {PurchaseContextService} from '../../shared/purchase-context.service';
import {I18nService} from '../../shared/i18n.service';
import {AnalyticsService} from '../../shared/analytics.service';
import {ReservationInfo, ReservationStatusInfo} from '../../model/reservation-info';
import {PaymentProxy} from '../../model/event';

const HELP = 'reservation.payment-processing.banchile.';
const PROVIDER_WARNING = 'reservation.payment-processing.warning.message';

/**
 * "Procesando pago" cuando el pago con Banchile tarda: después de un minuto explica qué está pasando
 * y que se puede cerrar la página. Sin esto, el comprador que volvió con "Atrás" o que no terminó de
 * pagar quedaba en "Por favor, espere" hasta que vencía la sesión (30 minutos).
 */
describe('ProcessingPaymentComponent', () => {
  let fixture: ComponentFixture<ProcessingPaymentComponent>;
  let component: ProcessingPaymentComponent;
  let reservationService: jasmine.SpyObj<ReservationService>;
  let router: jasmine.SpyObj<Router>;
  let currentStatus: ReservationStatusInfo;

  async function create(paymentProxy: PaymentProxy | null): Promise<void> {
    currentStatus = {status: 'EXTERNAL_PROCESSING_PAYMENT', validatedBookingInformation: true};
    reservationService = jasmine.createSpyObj<ReservationService>('ReservationService',
      ['getReservationInfo', 'getReservationStatusInfo', 'forcePaymentStatusCheck']);
    reservationService.getReservationInfo.and.returnValue(of({
      id: 'r1',
      status: 'EXTERNAL_PROCESSING_PAYMENT',
      paymentProxy,
      orderSummary: {free: false}
    } as unknown as ReservationInfo));
    reservationService.getReservationStatusInfo.and.callFake(() => of(currentStatus));
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.returnValue(Promise.resolve(true));
    const purchaseContextService = jasmine.createSpyObj<PurchaseContextService>('PurchaseContextService', ['getContext']);
    purchaseContextService.getContext.and.returnValue(of({analyticsConfiguration: {}} as any));

    await TestBed.configureTestingModule({
      declarations: [ProcessingPaymentComponent],
      imports: [TranslateModule.forRoot()],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        {provide: ActivatedRoute, useValue: {
          data: of({type: 'event', publicIdentifierParameter: 'eventShortName'}),
          params: of({eventShortName: 'bingo', reservationId: 'r1'}),
          snapshot: {queryParams: {}, params: {}}
        }},
        {provide: Router, useValue: router},
        {provide: ReservationService, useValue: reservationService},
        {provide: PurchaseContextService, useValue: purchaseContextService},
        {provide: I18nService, useValue: jasmine.createSpyObj('I18nService', ['setPageTitle'])},
        {provide: AnalyticsService, useValue: jasmine.createSpyObj('AnalyticsService', ['pageView'])}
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(ProcessingPaymentComponent);
    component = fixture.componentInstance;
  }

  function waitSeconds(seconds: number): void {
    tick(seconds * 1000);
    fixture.detectChanges();
  }

  function help(): HTMLElement | null {
    return fixture.nativeElement.querySelector('.banchile-payment-help');
  }

  function pageText(): string {
    return fixture.nativeElement.textContent;
  }

  describe('pagando con Banchile', () => {
    beforeEach(async () => create('BANCHILE'));

    it('después de un minuto explica qué hacer: terminar en Banchile o cerrar sin perder la reserva', fakeAsync(() => {
      fixture.detectChanges();

      waitSeconds(58);
      expect(help()).toBeNull();

      waitSeconds(2);
      expect(help()).not.toBeNull();
      expect(help().textContent).toContain(HELP + 'title');
      expect(help().textContent).toContain(HELP + 'still-paying');
      expect(help().textContent).toContain(HELP + 'not-paying');

      fixture.destroy();
    }));

    it('sigue consultando el estado mientras tanto', fakeAsync(() => {
      fixture.detectChanges();

      waitSeconds(60);

      expect(reservationService.getReservationStatusInfo).toHaveBeenCalledTimes(30);
      fixture.destroy();
    }));

    it('no dice que hay problemas con el proveedor de pago a los 4 minutos: el comprador puede seguir pagando', fakeAsync(() => {
      fixture.detectChanges();

      waitSeconds(270);

      expect(component.providerWarningVisible).toBeFalse();
      expect(pageText()).not.toContain(PROVIDER_WARNING);
      expect(help()).not.toBeNull();
      fixture.destroy();
    }));

    it('si llega el resultado antes, va a la página que corresponda y no muestra la explicación', fakeAsync(() => {
      fixture.detectChanges();
      waitSeconds(10);

      currentStatus = {status: 'COMPLETE', validatedBookingInformation: true};
      waitSeconds(2);

      expect(router.navigate).toHaveBeenCalledWith(['event', 'bingo', 'reservation', 'r1', 'success'], jasmine.anything());
      fixture.destroy();
      tick(60_000);
    }));
  });

  describe('con otro proveedor de pago', () => {
    beforeEach(async () => create('STRIPE'));

    it('no muestra la explicación de Banchile y mantiene el aviso de los 4 minutos', fakeAsync(() => {
      fixture.detectChanges();

      waitSeconds(60);
      expect(help()).toBeNull();

      waitSeconds(210);
      expect(component.providerWarningVisible).toBeTrue();
      expect(pageText()).toContain(PROVIDER_WARNING);
      fixture.destroy();
    }));
  });
});
