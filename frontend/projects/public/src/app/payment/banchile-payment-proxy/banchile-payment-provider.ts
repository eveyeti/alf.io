import {SimplePaymentProvider} from '../payment-provider';

export class BanchilePaymentProvider extends SimplePaymentProvider {
  override get paymentMethodDeferred(): boolean {
        return false;
    }
}
