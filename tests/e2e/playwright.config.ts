import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for Banchile/Alfio E2E tests.
 * Apunta al mirror local de Alfio corriendo en localhost:8080
 */
export default defineConfig({
  testDir: '.',
  fullyParallel: false, // serializar para evitar conflictos de reservas
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1, // un worker para serializar reservas de prueba
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
  ],
  use: {
    baseURL: 'http://localhost:8080',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    // User-agent real para evitar bloqueos de bot
    userAgent:
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) ' +
      'AppleWebKit/537.36 (KHTML, like Gecko) ' +
      'Chrome/120.0.0.0 Safari/537.36',
    // Viewport típico desktop
    viewport: { width: 1280, height: 800 },
    // Extraer artefactos en caso de fallo
    headless: true,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  // Timeout global: 60s por test (la sesión Banchile puede tardar)
  timeout: 60_000,
  // Timeout por acción: 15s
  expect: {
    timeout: 15_000,
  },
  outputDir: 'test-results',
});
