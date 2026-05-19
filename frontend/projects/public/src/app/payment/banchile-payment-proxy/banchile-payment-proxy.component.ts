import {Component, EventEmitter, Input, OnChanges, Output, SimpleChanges} from '@angular/core';

import {PaymentProvider} from '../payment-provider';
import {UntypedFormGroup} from '@angular/forms';
import {BanchilePaymentProvider} from './banchile-payment-provider';
import {PaymentMethod, PaymentProxy} from '../../model/event';

@Component({
  selector: 'app-banchile-payment-proxy',
  templateUrl: './banchile-payment-proxy.component.html'
})
export class BanchilePaymentProxyComponent implements OnChanges {

  @Input()
  method!: PaymentMethod;

  @Input()
  proxy!: PaymentProxy;

  @Input()
  parameters!: {[key: string]: any};

  @Input()
  overviewForm!: UntypedFormGroup;

  @Output()
  paymentProvider: EventEmitter<PaymentProvider> = new EventEmitter<PaymentProvider>();

  private compatibleMethods: PaymentMethod[] = ['CREDIT_CARD'];

  constructor() { }

  ngOnChanges(changes: SimpleChanges): void {
    if (this.matchProxyAndMethod && changes['method']) {
      this.paymentProvider.emit(new BanchilePaymentProvider());
    }
  }

  public get matchProxyAndMethod(): boolean {
    return (this.compatibleMethods.includes(this.method)) && this.proxy === 'BANCHILE';
  }

}
