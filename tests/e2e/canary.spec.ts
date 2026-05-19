/**
 * G.2 — Canary test: ¿El sandbox de Banchile Pagos permite interacción via Playwright?
 *
 * Este test es el gatekeeper del suite completo. Si falla, los demás tests de checkout
 * deben considerarse BLOQUEADOS por bot-detection o sandbox caído.
 *
 * Hallazgos en 2026-05-18:
 * - Sandbox accesible sin CAPTCHA ni bot-detection
 * - Requiere email antes de mostrar formulario de tarjeta
 * - Formulario de tarjeta: inputs directos (sin iframes)
 * - 3DS frictionless: el navegador maneja el challenge automáticamente
 */
import { test, expect } from '@playwright/test';
import { createSandboxSession, SANDBOX_CARDS } from './helpers/banchile-session';

/**
 * C.1 — Verificar que el sandbox responde y no bloquea Playwright
 */
test('canary: sandbox Banchile accesible sin bot-detection', async ({ page }) => {
  // Crear una sesión real en el sandbox
  const session = await createSandboxSession({
    reference: `CANARY-${Date.now()}`,
    description: 'Canary test Playwright bot detection',
    amountCLP: 1000,
  });

  expect(session.requestId).toBeGreaterThan(0);
  expect(session.processUrl).toContain('checkout.test.banchilepagos.cl');

  // Navegar al checkout
  await page.goto(session.processUrl, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);

  // Verificar que la página carga correctamente (sin Access Denied, Cloudflare, etc.)
  const pageText = await page.locator('body').innerText();
  expect(pageText).not.toContain('Access Denied');
  expect(pageText).not.toContain('CAPTCHA');
  expect(pageText).not.toContain('Cloudflare');
  expect(pageText).not.toContain('blocked');

  // Verificar que es el checkout de Banchile real
  expect(pageText).toContain('Modo de pruebas');

  const title = await page.title();
  expect(title).toContain('Banchile');
});

/**
 * C.2 — Verificar que el formulario de email está accesible
 */
test('canary: formulario email visible y clickeable', async ({ page }) => {
  const session = await createSandboxSession({
    reference: `CANARY-EMAIL-${Date.now()}`,
    description: 'Canary email form test',
    amountCLP: 1000,
  });

  await page.goto(session.processUrl, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);

  const emailInput = page.locator(
    'input[type="email"], input[name="email"], input[placeholder*="correo"], input[placeholder*="email"]'
  );
  await expect(emailInput.first()).toBeVisible({ timeout: 10000 });

  // Podemos escribir en él
  await emailInput.first().fill('canary@banchile-e2e.local');
  await expect(emailInput.first()).toHaveValue('canary@banchile-e2e.local');
});

/**
 * C.3 — Verificar que el formulario de tarjeta es accesible tras el email
 */
test('canary: formulario tarjeta accesible tras paso email', async ({ page }) => {
  const session = await createSandboxSession({
    reference: `CANARY-CARD-${Date.now()}`,
    description: 'Canary card form access',
    amountCLP: 1000,
  });

  await page.goto(session.processUrl, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);

  // Completar email
  await page.locator('input[type="email"], input[name="email"], input[placeholder*="correo"]')
    .first()
    .fill('canary@banchile-e2e.local');
  await page.locator('button:has-text("Continuar")').first().click();
  await page.waitForTimeout(2000);

  // El formulario de tarjeta debe aparecer SIN iframes (acceso directo)
  await expect(page.locator('input[name="cardNumber"]')).toBeVisible({ timeout: 10000 });
  await expect(page.locator('input[name="date"]')).toBeVisible({ timeout: 5000 });
  await expect(page.locator('input[name="cvv"]')).toBeVisible({ timeout: 5000 });

  // No debe haber bot-detection
  const iframeCount = await page.locator('iframe').count();
  // Los campos de tarjeta están directamente en la página (no en iframes de seguridad)
  expect(iframeCount).toBe(0);

  // CANARY PASSED: podemos interactuar con el formulario de tarjeta
  await page.locator('input[name="cardNumber"]').fill(SANDBOX_CARDS.APPROVED.number);
  // Banchile puede formatear el número (con espacios). Verificamos que contiene '1111'
  const cardValue = await page.locator('input[name="cardNumber"]').inputValue();
  expect(cardValue).toContain('1111');
});
