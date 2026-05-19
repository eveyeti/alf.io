/**
 * G.3 — Happy Path: pago completo con tarjeta aprobada en Banchile sandbox.
 *
 * Escenario: Usuario navega al checkout de Banchile (processUrl ya creada),
 * completa el formulario de tarjeta, y el pago es aprobado.
 *
 * Nota sobre el approach: creamos la sesión Banchile directamente via API
 * (sin pasar por Alfio) porque el flujo de reserva en Alfio requiere
 * navegación UI compleja (CSRF, sesión, Angular SPA). Esto aísla el test
 * al comportamiento de Banchile y del webhook.
 *
 * Para tests end-to-end COMPLETOS (desde Alfio → Banchile → Alfio), ver:
 * - scripts/e2e-full-flow.ts (TODO: Phase H)
 */
import { test, expect } from '@playwright/test';
import {
  createSandboxSession,
  querySandboxSession,
  SANDBOX_CARDS,
} from './helpers/banchile-session';
import { completeBanchileCheckout } from './helpers/alfio-page';

/**
 * H.1 — Happy path: pago aprobado con Visa 4111111111111111
 *
 * Verifica:
 * 1. Redirect a checkout.test.banchilepagos.cl
 * 2. Formulario de email y tarjeta accesible
 * 3. Pago procesado con éxito (3DS frictionless)
 * 4. Estado final "Transacción Aprobada"
 * 5. querySession confirma APPROVED en la API de Banchile
 */
test('happy path: pago aprobado con tarjeta Visa 4111...1111', async ({ page }) => {
  // Step 1: Crear sesión Banchile (simula lo que hace BanchilePagosWebhookManager.doPayment)
  const session = await createSandboxSession({
    reference: `HAPPY-${Date.now()}`,
    description: 'Happy path E2E Playwright Banchile integration',
    amountCLP: 12000,
    returnUrl: 'http://localhost:8080/event/tributo_soda/reservation/TEST_HAPPY/book',
  });

  expect(session.requestId).toBeGreaterThan(0);
  expect(session.processUrl).toContain('checkout.test.banchilepagos.cl');
  console.log(`[happy-path] requestId=${session.requestId} url=${session.processUrl}`);

  // Step 2: Navegar al checkout de Banchile
  await page.goto(session.processUrl, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);

  // Verificar que estamos en el checkout correcto
  expect(page.url()).toContain('checkout.test.banchilepagos.cl');
  await expect(page.locator('text=Modo de pruebas')).toBeVisible({ timeout: 10000 });

  // Step 3: Completar checkout con tarjeta aprobada
  const result = await completeBanchileCheckout(page, SANDBOX_CARDS.APPROVED, {
    email: 'happy-path@banchile-e2e.local',
    maxWaitSeconds: 60,
  });

  // Step 4: Verificar resultado en la UI de Banchile
  expect(result).toBe('approved');

  const pageText = await page.locator('body').innerText();
  expect(pageText).toMatch(/Aprobad[ao]/);
  console.log('[happy-path] Pago aprobado en UI de Banchile');

  // Step 5: Verificar estado via querySession en API de Banchile
  // (confirma que el estado no fue solo visual sino que Banchile lo registró)
  const queryResponse = await querySandboxSession(session.requestId);
  expect(queryResponse.status.status).toBe('APPROVED');
  console.log(`[happy-path] querySession status=${queryResponse.status.status} ✓`);

  // Step 6: Tomar screenshot de la confirmación
  await page.screenshot({
    path: 'test-results/happy-path-approved.png',
    fullPage: true,
  });
});

/**
 * H.2 — Happy path: verifica "Volver al comercio" después de pago aprobado
 *
 * En el happy path real con Alfio, Banchile redirige al returnUrl.
 * Este test verifica que la URL de retorno está en el dominio correcto.
 */
test('happy path: redirect "Volver al comercio" apunta a returnUrl', async ({ page }) => {
  const returnUrl = 'http://localhost:8080/event/tributo_soda/reservation/REDIRECT_TEST/book';

  const session = await createSandboxSession({
    reference: `REDIRECT-${Date.now()}`,
    description: 'Redirect test E2E',
    amountCLP: 12000,
    returnUrl,
  });

  await page.goto(session.processUrl, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);

  const result = await completeBanchileCheckout(page, SANDBOX_CARDS.APPROVED, {
    email: 'redirect-test@banchile-e2e.local',
    maxWaitSeconds: 60,
  });

  expect(result).toBe('approved');

  // Verificar que el botón "Volver al comercio" está presente
  const returnBtn = page.locator(
    'button:has-text("Volver al comercio"), a:has-text("Volver al comercio")'
  );
  await expect(returnBtn.first()).toBeVisible({ timeout: 10000 });

  // Obtener la URL de destino del botón (sin hacer click, para no depender de Alfio corriendo)
  const href = await returnBtn.first().getAttribute('href');
  // El href puede ser null si es un botón, pero el click debería redirigir al returnUrl
  console.log(`[redirect-test] Botón "Volver al comercio" visible, href=${href ?? '(button)'}`);

  // Para verificar el redirect real, navegar y ver a dónde va
  // Nota: localhost:8080/event/.../reservation/REDIRECT_TEST/book retornará 404
  // porque REDIRECT_TEST no es un ID real, pero el redirect debería ocurrir
  await Promise.all([
    page.waitForResponse(
      (resp) => resp.url().includes('localhost:8080'),
      { timeout: 15000 }
    ).catch(() => null),
    returnBtn.first().click(),
  ]);

  // Esperamos que la URL incluya localhost:8080 (aunque retorne 404 por el ID falso)
  await page.waitForURL(/localhost:8080/, { timeout: 10000 }).catch(() => null);
  const finalUrl = page.url();
  console.log(`[redirect-test] URL final: ${finalUrl}`);
  expect(finalUrl).toContain('localhost:8080');

  await page.screenshot({
    path: 'test-results/happy-path-redirect.png',
    fullPage: true,
  });
});
