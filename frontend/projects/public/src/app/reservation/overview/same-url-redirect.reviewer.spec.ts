import {Component, inject} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';

@Component({standalone: true, template: 'resumen'})
class StubOverviewComponent {
  static created = 0;
  constructor() {
    StubOverviewComponent.created++;
  }
}

@Component({standalone: true, template: 'procesando pago'})
class StubProcessingPaymentComponent {
}

/**
 * Revisor (v6, ronda 3): la app usa RouterModule.forRoot(routes) sin opciones, así que
 * onSameUrlNavigation es 'ignore'. Si el guard de "procesando pago" devuelve el resumen en el que ya
 * está el comprador (la reserva pasó a PENDING entre la consulta del pageshow y la del guard), la
 * redirección se ignora: el resumen no se vuelve a crear, no corre loadReservation y sigue con
 * submitting = true ("Por favor, espere") hasta que el comprador recargue.
 */
describe('Router: un guard que redirige a la URL actual (revisor v6 r3)', () => {
  beforeEach(() => {
    StubOverviewComponent.created = 0;
    TestBed.configureTestingModule({
      providers: [provideRouter([
        {path: 'overview', component: StubOverviewComponent},
        {path: 'processing-payment', component: StubProcessingPaymentComponent, canActivate: [() => inject(Router).parseUrl('/overview')]}
      ])]
    });
  });

  it('documenta: la redirección al resumen en el que ya está se ignora y el resumen no se recrea', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/overview');
    expect(StubOverviewComponent.created).toBe(1);
    const router = TestBed.inject(Router);

    await router.navigate(['/processing-payment'], {replaceUrl: true});

    expect(router.url).toBe('/overview');
    expect(StubOverviewComponent.created).toBe(1);
  });
});
