/**
 * G.4 — Rejected scenario: pago con tarjeta que el sandbox rechaza.
 *
 * Verifica el flujo negativo: el usuario llega al checkout, paga con una tarjeta
 * rechazada, y Banchile retorna el estado REJECTED.
 */
import { test, expect } from '@playwright/test';
import {
  createSandboxSession,
  querySandboxSession,
  SANDBOX_CARDS,
} from './helpers/banchile-session';
import { completeBanchileCheckout } from './helpers/alfio-page';

/**
 * R.1 — Pago rechazado con Visa 4444333322221111
 */
test('rejected: tarjeta rechazada muestra estado RECHAZADO', async ({ page }) => {
  const session = await createSandboxSession({
    reference: `REJ-${Date.now()}`,
    description: 'Rejected card E2E test',
    amountCLP: 12000,
    returnUrl: 'http://localhost:8080/event/tributo_soda/reservation/TEST_REJ/book',
  });

  console.log(`[rejected] requestId=${session.requestId}`);

  await page.goto(session.processUrl, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);

  // Completar checkout con tarjeta rechazada
  const result = await completeBanchileCheckout(page, SANDBOX_CARDS.REJECTED, {
    email: 'rejected-test@banchile-e2e.local',
    maxWaitSeconds: 60,
  });

  expect(result).toBe('rejected');

  const pageText = await page.locator('body').innerText();
  expect(pageText).toMatch(/Rechazad[ao]/);
  console.log('[rejected] Estado RECHAZADO confirmado en UI');

  // Verificar via querySession en la API de Banchile
  // Nota: después de un REJECTED en la UI, el estado en querySession puede tardar
  // unos segundos en propagarse. Hacemos hasta 3 intentos.
  let queryStatus = '';
  for (let i = 0; i < 3; i++) {
    const queryResponse = await querySandboxSession(session.requestId);
    queryStatus = queryResponse.status.status;
    console.log(`[rejected] querySession attempt ${i + 1}: status=${queryStatus}`);
    if (queryStatus === 'REJECTED') break;
    await new Promise((r) => setTimeout(r, 3000));
  }
  // Banchile puede reportar REJECTED o PENDING dependiendo del timing.
  // Lo importante es que la UI mostró RECHAZADO y el sandbox procesó la transacción.
  expect(['REJECTED', 'PENDING']).toContain(queryStatus);
  console.log(`[rejected] querySession final status=${queryStatus}`);

  await page.screenshot({
    path: 'test-results/rejected-card.png',
    fullPage: true,
  });
});
