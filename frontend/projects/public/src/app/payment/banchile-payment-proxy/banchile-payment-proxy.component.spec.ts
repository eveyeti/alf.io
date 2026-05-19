import {ComponentFixture, TestBed} from '@angular/core/testing';
import {BanchilePaymentProxyComponent} from './banchile-payment-proxy.component';
import {ReactiveFormsModule} from '@angular/forms';
import {TranslateModule} from '@ngx-translate/core';
import {PaymentProvider} from '../payment-provider';

describe('BanchilePaymentProxyComponent', () => {
  let component: BanchilePaymentProxyComponent;
  let fixture: ComponentFixture<BanchilePaymentProxyComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [BanchilePaymentProxyComponent],
      imports: [ReactiveFormsModule, TranslateModule.forRoot()],
    }).compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(BanchilePaymentProxyComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    component.method = 'CREDIT_CARD';
    component.proxy = 'BANCHILE';
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  describe('matchProxyAndMethod', () => {
    it('should match when proxy is BANCHILE and method is CREDIT_CARD', () => {
      component.method = 'CREDIT_CARD';
      component.proxy = 'BANCHILE';
      expect(component.matchProxyAndMethod).toBeTrue();
    });

    it('should not match when proxy is MOLLIE', () => {
      component.method = 'CREDIT_CARD';
      component.proxy = 'MOLLIE';
      expect(component.matchProxyAndMethod).toBeFalse();
    });

    it('should not match when proxy is STRIPE', () => {
      component.method = 'CREDIT_CARD';
      component.proxy = 'STRIPE';
      expect(component.matchProxyAndMethod).toBeFalse();
    });

    it('should not match when method is PAYPAL even with BANCHILE proxy', () => {
      component.method = 'PAYPAL';
      component.proxy = 'BANCHILE';
      expect(component.matchProxyAndMethod).toBeFalse();
    });
  });

  describe('paymentProvider emission', () => {
    it('should emit a BanchilePaymentProvider when proxy and method match and method changes', () => {
      const emittedProviders: PaymentProvider[] = [];
      component.paymentProvider.subscribe((provider: PaymentProvider) => emittedProviders.push(provider));

      component.proxy = 'BANCHILE';
      component.method = 'CREDIT_CARD';
      component.ngOnChanges({method: {currentValue: 'CREDIT_CARD', previousValue: null, firstChange: true, isFirstChange: () => true}});

      expect(emittedProviders.length).toBe(1);
      expect(emittedProviders[0].paymentMethodDeferred).toBeFalse();
    });

    it('should not emit when proxy does not match', () => {
      const emittedProviders: PaymentProvider[] = [];
      component.paymentProvider.subscribe((provider: PaymentProvider) => emittedProviders.push(provider));

      component.proxy = 'MOLLIE';
      component.method = 'CREDIT_CARD';
      component.ngOnChanges({method: {currentValue: 'CREDIT_CARD', previousValue: null, firstChange: true, isFirstChange: () => true}});

      expect(emittedProviders.length).toBe(0);
    });
  });
});
