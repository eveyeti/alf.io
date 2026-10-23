import {ComponentFixture, TestBed} from '@angular/core/testing';
import {FaIconLibrary, FontAwesomeModule} from '@fortawesome/angular-fontawesome';
import {faExclamationTriangle} from '@fortawesome/free-solid-svg-icons';
import {TranslateModule, TranslateService} from '@ngx-translate/core';
import {BanchileFailedPaymentNoticeComponent} from './banchile-failed-payment-notice.component';
import {PaymentDetailsInfo} from '../../model/reservation-info';

const KEY = 'reservation-page.payment.banchile.failed-attempt.';

/** Los textos de public_es.properties, como los entrega /api/v2/public/i18n/bundle/es. */
const SPANISH = {
  [KEY + 'rejected']: 'Tu banco no aprobó el pago y no se hizo ningún cargo. Tu reserva sigue guardada: '
    + 'puedes intentarlo de nuevo, con la misma tarjeta o con otra.',
  [KEY + 'cancelled']: 'Cancelaste el pago y no se hizo ningún cargo. Tu reserva sigue guardada: puedes volver a pagar antes de que venza.',
  [KEY + 'expired']: 'Se acabó el tiempo para pagar en Banchile y no se hizo ningún cargo. Tu reserva sigue guardada: '
    + 'puedes intentarlo de nuevo.',
  [KEY + 'bank-detail']: 'Detalle del banco: {{0}}'
};

/** Los textos de public.properties (inglés), como los entrega /api/v2/public/i18n/bundle/en. */
const ENGLISH = {
  [KEY + 'rejected']: 'Your bank did not approve the payment and you have not been charged. Your reservation is still on hold: '
    + 'you can try again, with the same card or a different one.',
  [KEY + 'cancelled']: 'You cancelled the payment and you have not been charged. Your reservation is still on hold: '
    + 'you can pay again before it expires.',
  [KEY + 'expired']: 'The time to pay on Banchile ran out and you have not been charged. Your reservation is still on hold: '
    + 'you can try again.',
  [KEY + 'bank-detail']: 'Message from the bank: {{0}}'
};

describe('BanchileFailedPaymentNoticeComponent', () => {
  let fixture: ComponentFixture<BanchileFailedPaymentNoticeComponent>;
  let component: BanchileFailedPaymentNoticeComponent;
  let translate: TranslateService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [BanchileFailedPaymentNoticeComponent],
      imports: [FontAwesomeModule, TranslateModule.forRoot()],
    }).compileComponents();
    // los íconos que registra AppModule
    TestBed.inject(FaIconLibrary).addIcons(faExclamationTriangle);
    translate = TestBed.inject(TranslateService);
    translate.setTranslation('es', SPANISH);
    translate.setTranslation('en', ENGLISH);
    translate.use('es');
    fixture = TestBed.createComponent(BanchileFailedPaymentNoticeComponent);
    component = fixture.componentInstance;
  });

  function render(paymentDetails: PaymentDetailsInfo | null): HTMLElement | null {
    component.paymentDetails = paymentDetails;
    fixture.detectChanges();
    return fixture.nativeElement.querySelector('.alert');
  }

  function text(element: Element | null): string {
    return element == null ? null : element.textContent.replace(/\s+/g, ' ').trim();
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

  describe('el texto depende del motivo', () => {
    it('rechazo del banco: el texto del rechazo y, debajo, el mensaje del banco sin el código', () => {
      const notice = render({status: 'REJECTED', statusReason: '51', statusMessage: 'Fondos insuficientes'});

      expect(text(notice.querySelector('.failed-attempt-text'))).toBe(SPANISH[KEY + 'rejected']);
      expect(text(notice.querySelector('.failed-attempt-bank-detail'))).toBe('Detalle del banco: Fondos insuficientes');
      expect(text(notice)).not.toContain('51');
    });

    it('rechazo del banco sin mensaje: sin la línea del banco', () => {
      const notice = render({status: 'REJECTED', statusReason: '05'});

      expect(text(notice.querySelector('.failed-attempt-text'))).toBe(SPANISH[KEY + 'rejected']);
      expect(notice.querySelector('.failed-attempt-bank-detail')).toBeNull();
    });

    it('cancelado por el comprador (?C): el texto de la cancelación, sin el mensaje ni el código', () => {
      const notice = render({status: 'REJECTED', statusReason: '?C', statusMessage: 'La petición ha sido cancelada por el usuario'});

      expect(text(notice)).toBe(SPANISH[KEY + 'cancelled']);
      expect(notice.querySelector('.failed-attempt-bank-detail')).toBeNull();
    });

    it('vencido (EX): el texto del vencimiento, sin el mensaje ni el código', () => {
      const notice = render({status: 'REJECTED', statusReason: 'EX', statusMessage: 'La petición ha expirado'});

      expect(text(notice)).toBe(SPANISH[KEY + 'expired']);
    });

    it('pago parcial vencido: el texto del vencimiento', () => {
      expect(text(render({status: 'PARTIAL_EXPIRED', statusReason: 'XN'}))).toBe(SPANISH[KEY + 'expired']);
    });

    it('en el idioma de la página', () => {
      translate.use('en');
      const notice = render({status: 'REJECTED', statusReason: '51', statusMessage: 'Fondos insuficientes'});

      expect(text(notice.querySelector('.failed-attempt-text'))).toBe(ENGLISH[KEY + 'rejected']);
      expect(text(notice.querySelector('.failed-attempt-bank-detail'))).toBe('Message from the bank: Fondos insuficientes');
    });
  });

  /**
   * Con una tarjeta rechazada Banchile deja la sesión abierta; si el comprador vuelve al comercio, la
   * cierra con ?C, y si no hace nada, vence (EX). Lo que pasó fue el rechazo del banco: manda el
   * último intento, no la razón de la sesión (QA del 15-oct, sesiones 4406 y 4412).
   */
  describe('el último intento manda sobre la razón de la sesión', () => {
    const RISK_REJECTION = 'Transacción declinada por políticas de control de riesgo';

    it('rechazo del banco y después "Volver al comercio" (?C): el texto del rechazo, con el mensaje del intento', () => {
      const notice = render({
        status: 'REJECTED', statusReason: '?C', statusMessage: 'La petición ha sido cancelada por el usuario',
        attemptStatus: 'REJECTED', attemptReason: '?2', attemptMessage: RISK_REJECTION
      });

      expect(text(notice.querySelector('.failed-attempt-text'))).toBe(SPANISH[KEY + 'rejected']);
      expect(text(notice.querySelector('.failed-attempt-bank-detail'))).toBe('Detalle del banco: ' + RISK_REJECTION);
      expect(text(notice)).not.toContain('?2');
      expect(text(notice)).not.toContain('cancelada');
    });

    it('rechazo del banco y después la sesión vencida (EX): el texto del rechazo', () => {
      const notice = render({
        status: 'REJECTED', statusReason: 'EX', statusMessage: 'La petición ha expirado',
        attemptStatus: 'REJECTED', attemptReason: '?2', attemptMessage: RISK_REJECTION
      });

      expect(text(notice.querySelector('.failed-attempt-text'))).toBe(SPANISH[KEY + 'rejected']);
      expect(text(notice.querySelector('.failed-attempt-bank-detail'))).toBe('Detalle del banco: ' + RISK_REJECTION);
    });

    it('intento rechazado sin mensaje del banco: el texto del rechazo, sin la línea del banco', () => {
      const notice = render({
        status: 'REJECTED', statusReason: '?C', statusMessage: 'La petición ha sido cancelada por el usuario',
        attemptStatus: 'REJECTED', attemptReason: '05'
      });

      expect(text(notice.querySelector('.failed-attempt-text'))).toBe(SPANISH[KEY + 'rejected']);
      expect(notice.querySelector('.failed-attempt-bank-detail')).toBeNull();
    });

    it('sin intentos y cancelado (?C): el texto de la cancelación', () => {
      const notice = render({status: 'REJECTED', statusReason: '?C', statusMessage: 'La petición ha sido cancelada por el usuario'});

      expect(text(notice)).toBe(SPANISH[KEY + 'cancelled']);
    });

    it('sin intentos y vencido (EX): el texto del vencimiento', () => {
      const notice = render({status: 'REJECTED', statusReason: 'EX', statusMessage: 'La petición ha expirado'});

      expect(text(notice)).toBe(SPANISH[KEY + 'expired']);
    });

    it('un intento sin respuesta del banco y después la sesión vencida: el texto del vencimiento', () => {
      const notice = render({
        status: 'REJECTED', statusReason: 'EX', statusMessage: 'La petición ha expirado',
        attemptStatus: 'PENDING', attemptReason: 'PT', attemptMessage: 'La transacción se encuentra pendiente'
      });

      expect(text(notice)).toBe(SPANISH[KEY + 'expired']);
    });

    it('en inglés, el mensaje del intento va en la línea del banco', () => {
      translate.use('en');
      const notice = render({
        status: 'REJECTED', statusReason: '?C', statusMessage: 'La petición ha sido cancelada por el usuario',
        attemptStatus: 'REJECTED', attemptReason: '?2', attemptMessage: RISK_REJECTION
      });

      expect(text(notice.querySelector('.failed-attempt-text'))).toBe(ENGLISH[KEY + 'rejected']);
      expect(text(notice.querySelector('.failed-attempt-bank-detail'))).toBe('Message from the bank: ' + RISK_REJECTION);
    });
  });
});
