/**
 * Page object helpers para navegar por la UI de Alfio en los tests E2E.
 *
 * Encapsula la navegación, llenado de formularios y aserciones específicas
 * de Alfio (Angular SPA, CSRF, sesión).
 */
import { type Page, expect } from '@playwright/test';

const ALFIO_BASE = process.env.ALFIO_BASE_URL ?? 'http://localhost:8080';
const EVENT_SLUG = process.env.EVENT_SLUG ?? 'tributo_soda';

/**
 * Navega a la página pública del evento y espera a que el SPA Angular cargue.
 */
export async function navigateToEvent(page: Page): Promise<void> {
  await page.goto(`${ALFIO_BASE}/event/${EVENT_SLUG}`, {
    waitUntil: 'domcontentloaded',
  });
  // Esperar que Angular hidrate el componente principal
  await page.waitForTimeout(2000);
}

/**
 * Navega directamente al overview de una reserva.
 * Requiere que la reserva exista y tenga estado PENDING o EXTERNAL_PROCESSING_PAYMENT.
 */
export async function navigateToReservationOverview(
  page: Page,
  reservationId: string
): Promise<void> {
  const url = `${ALFIO_BASE}/event/${EVENT_SLUG}/reservation/${reservationId}/overview`;
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
}

/**
 * Crea una reserva via UI de Alfio:
 * 1. Navega al evento
 * 2. Hace click en "Inscribirse" (o selecciona cantidad)
 * 3. Continúa al formulario de contacto
 * 4. Completa datos básicos
 * 5. Retorna el reservationId de la URL
 *
 * NOTA: Esto es lo que haría un usuario real. Requiere que haya tickets disponibles.
 */
export async function createReservationViaUI(
  page: Page,
  opts: {
    firstName?: string;
    lastName?: string;
    email?: string;
    categoryId?: number;
  } = {}
): Promise<string> {
  const {
    firstName = 'Test',
    lastName = 'Playwright',
    email = `playwright-e2e-${Date.now()}@banchile-e2e.local`,
    categoryId = 2,
  } = opts;

  await navigateToEvent(page);

  // Esperar que aparezca el botón para agregar tickets
  // El SPA carga el listado de categorías
  await page.waitForSelector('[data-cy="ticket-category"], .ticket-category, .add-to-cart', {
    timeout: 10000,
  }).catch(() => null);

  // Click en "+" para agregar 1 ticket
  const addBtn = page.locator(`[data-quantity-target="${categoryId}"] button.add, button.add-ticket, button:has-text("+")`)
    .or(page.locator('button.add-btn, button.btn-add, [aria-label*="add"], [aria-label*="agregar"]'))
    .first();

  const addCount = await addBtn.count();
  if (addCount === 0) {
    throw new Error('No se encontró botón para agregar tickets');
  }
  await addBtn.click();
  await page.waitForTimeout(500);

  // Click "Reservar" o "Continuar"
  const reservarBtn = page.locator('button:has-text("Reservar"), button:has-text("Continuar"), button:has-text("Reserve"), button:has-text("Continue")')
    .first();
  await reservarBtn.click();
  await page.waitForTimeout(2000);

  // Formulario de contacto — completar datos
  await page.locator('input[name="firstName"], input[id*="firstName"], input[placeholder*="nombre"]')
    .first()
    .fill(firstName)
    .catch(() => null);
  await page.locator('input[name="lastName"], input[id*="lastName"], input[placeholder*="apellido"]')
    .first()
    .fill(lastName)
    .catch(() => null);
  await page.locator('input[name="email"], input[type="email"]')
    .first()
    .fill(email)
    .catch(() => null);

  // Continuar al resumen
  const continueBtn = page.locator('button:has-text("Continuar"), button:has-text("Continue"), button[type="submit"]').first();
  await continueBtn.click();
  await page.waitForTimeout(2000);

  // Extraer reservationId de la URL
  const url = page.url();
  const match = url.match(/reservation\/([a-f0-9-]{36})/);
  if (!match) {
    throw new Error(`No se pudo extraer reservationId de URL: ${url}`);
  }
  return match[1];
}

/**
 * En la página de overview de una reserva, hace click en el botón de pago Banchile.
 * Retorna true si el click fue exitoso y se inició la redirección.
 */
export async function clickBanchilePayButton(page: Page): Promise<boolean> {
  // El botón puede estar en distintos estados según la implementación Angular
  const selectors = [
    'button:has-text("Pagar con tarjeta (Banchile Pagos)")',
    'button:has-text("Banchile")',
    'button:has-text("Banchile Pagos")',
    '[data-payment-proxy="BANCHILE"]',
    'button.banchile-pay',
  ];

  for (const sel of selectors) {
    const count = await page.locator(sel).count();
    if (count > 0) {
      await page.locator(sel).first().click();
      return true;
    }
  }
  return false;
}

/**
 * Completa el formulario de tarjeta en el checkout de Banchile Pagos.
 * Asume que ya estamos en la URL checkout.test.banchilepagos.cl
 *
 * @param card Datos de la tarjeta a usar
 * @returns 'approved' | 'rejected' | 'processing' según el resultado final
 */
export async function completeBanchileCheckout(
  page: Page,
  card: { number: string; expiry: string; cvv: string },
  opts: { email?: string; maxWaitSeconds?: number } = {}
): Promise<'approved' | 'rejected' | 'processing' | 'error'> {
  const { email = 'playwright-e2e@banchile-e2e.local', maxWaitSeconds = 60 } = opts;

  // Step 1: Ingresar email
  const emailLocator = page.locator(
    'input[type="email"], input[name="email"], input[placeholder*="correo"], input[placeholder*="email"]'
  );
  await emailLocator.first().waitFor({ state: 'visible', timeout: 10000 });
  await emailLocator.first().fill(email);

  await page.locator('button:has-text("Continuar"), button:has-text("Continue")').first().click();
  await page.waitForTimeout(2000);

  // Step 2: Llenar datos de tarjeta
  await page.locator('input[name="cardNumber"]').waitFor({ state: 'visible', timeout: 10000 });
  await page.locator('input[name="cardNumber"]').fill(card.number);
  await page.locator('input[name="date"]').fill(card.expiry);
  await page.locator('input[name="cvv"]').fill(card.cvv);

  // Step 3: Hacer click en Pagar
  await page.locator('button:has-text("Pagar"), button:has-text("Pay")').first().click();

  // Step 4: Esperar resultado
  const deadline = Date.now() + maxWaitSeconds * 1000;
  while (Date.now() < deadline) {
    await page.waitForTimeout(3000);
    const text = await page.locator('body').innerText().catch(() => '');

    if (text.includes('Aprobada') || text.includes('Aprobado') || text.includes('Transacción Aprobada')) {
      return 'approved';
    }
    if (text.includes('Rechazada') || text.includes('Rechazado') || text.includes('rechazada')) {
      return 'rejected';
    }
    if (text.includes('Error') && !text.includes('Espera')) {
      return 'error';
    }
  }
  return 'processing';
}

/**
 * Desde la pantalla de resultado de Banchile, hace click en "Volver al comercio"
 * y espera la navegación de vuelta a Alfio.
 */
export async function clickReturnToMerchant(page: Page): Promise<void> {
  await page.locator('button:has-text("Volver al comercio"), a:has-text("Volver al comercio")')
    .first()
    .click();
  // Esperar navegación de vuelta a Alfio
  await page.waitForURL(/localhost:8080/, { timeout: 15000 }).catch(() => null);
}
