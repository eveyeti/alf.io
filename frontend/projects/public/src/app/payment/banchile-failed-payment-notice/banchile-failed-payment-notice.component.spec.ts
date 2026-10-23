import {ComponentFixture, TestBed} from '@angular/core/testing';
import {FaIconLibrary, FontAwesomeModule} from '@fortawesome/angular-fontawesome';
import {faExclamationTriangle} from '@fortawesome/free-solid-svg-icons';
import {TranslateModule} from '@ngx-translate/core';
import {BanchileFailedPaymentNoticeComponent} from './banchile-failed-payment-notice.component';
import {PaymentDetailsInfo} from '../../model/reservation-info';

describe('BanchileFailedPaymentNoticeComponent', () => {
  let fixture: ComponentFixture<BanchileFailedPaymentNoticeComponent>;
  let component: BanchileFailedPaymentNoticeComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [BanchileFailedPaymentNoticeComponent],
      imports: [FontAwesomeModule, TranslateModule.forRoot()],
    }).compileComponents();
    // los íconos que registra AppModule
    TestBed.inject(FaIconLibrary).addIcons(faExclamationTriangle);
    fixture = TestBed.createComponent(BanchileFailedPaymentNoticeComponent);
    component = fixture.componentInstance;
  });

  function render(paymentDetails: PaymentDetailsInfo | null): HTMLElement | null {
    component.paymentDetails = paymentDetails;
    fixture.detectChanges();
    return fixture.nativeElement.querySelector('.alert');
  }

  describe('se muestra tras cualquier intento fallido', () => {
    it('un rechazo de tarjeta, aunque Banchile haya mandado un código de autorización', () => {
      // el "000000" del intento rechazado: el backend ya no lo expone, pero el aviso no depende de eso
      const notice = render({status: 'REJECTED', statusReason: '05', statusMessage: 'Rechazada', authorization: '000000'});
      expect(notice).not.toBeNull();
    });

    it('un rechazo sin mensaje del banco', () => {
      expect(render({status: 'REJECTED', statusReason: '05'})).not.toBeNull();
    });

    it('una sesión cancelada por el comprador', () => {
      expect(render({status: 'REJECTED', statusReason: '?C', statusMessage: 'La petición ha sido cancelada por el usuario'}))
        .not.toBeNull();
    });

    it('una sesión vencida', () => {
      expect(render({status: 'REJECTED', statusReason: 'EX', statusMessage: 'La petición ha expirado'})).not.toBeNull();
    });

    it('un pago parcial vencido', () => {
      expect(render({status: 'PARTIAL_EXPIRED', statusReason: 'EX'})).not.toBeNull();
    });

    it('con el ícono de advertencia registrado en la app', () => {
      const notice = render({status: 'REJECTED', statusReason: '05'});
      expect(notice.querySelector('svg[data-icon="exclamation-triangle"]')).not.toBeNull();
    });
  });

  describe('no se muestra', () => {
    it('con el pago aprobado', () => {
      expect(render({status: 'APPROVED', statusReason: '00', statusMessage: 'Aprobada', authorization: '685559'})).toBeNull();
    });

    it('con el pago aprobado y después reembolsado', () => {
      expect(render({status: 'REFUNDED', authorization: '004393'})).toBeNull();
    });

    it('sin detalles del pago (otra pasarela o ningún intento)', () => {
      expect(render(null)).toBeNull();
      expect(render(undefined)).toBeNull();
    });

    it('sin el estado de la sesión', () => {
      expect(render({statusMessage: 'Rechazada'})).toBeNull();
    });
  });
});
