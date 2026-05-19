/**
 * Helper para crear sesiones Banchile Pagos sandbox directamente vía API.
 * Usado en tests que necesitan una processUrl real sin pasar por el flujo completo de Alfio.
 *
 * Credenciales sandbox: públicas según documentación oficial de Banchile Pagos.
 */
import crypto from 'crypto';
import https from 'https';

const SANDBOX_LOGIN = process.env.BANCHILE_LOGIN ?? 'ffb78b93826239e1aa85a515aa961bd9';
const SANDBOX_TRAN_KEY = process.env.BANCHILE_TRANKEY ?? 'U87nG0kcCsjb61Mj';
const SANDBOX_BASE_URL = process.env.BANCHILE_BASE_URL ?? 'https://checkout.test.banchilepagos.cl';

/**
 * Tarjetas de prueba del sandbox de Banchile Pagos (PlacetoPay gateway).
 *
 * Verificadas empíricamente en 2026-05-18 contra el sandbox.
 */
export const SANDBOX_CARDS = {
  /** Tarjeta Visa que resulta en APPROVED (3DS frictionless) */
  APPROVED: { number: '4111111111111111', expiry: '12/30', cvv: '123' },
  /** Tarjeta Visa que resulta en REJECTED */
  REJECTED: { number: '4444333322221111', expiry: '12/30', cvv: '123' },
} as const;

interface BanchileAuth {
  login: string;
  tranKey: string;
  nonce: string;
  seed: string;
}

function buildAuth(login: string, tranKey: string): BanchileAuth {
  const nonceBytes = crypto.randomBytes(16);
  const seed = new Date().toISOString();
  const sha1 = crypto.createHash('sha1');
  sha1.update(nonceBytes);
  sha1.update(seed, 'utf8');
  sha1.update(tranKey, 'utf8');
  return {
    login,
    tranKey: sha1.digest('base64'),
    nonce: nonceBytes.toString('base64'),
    seed,
  };
}

export interface CreateSessionOptions {
  reference?: string;
  description?: string;
  amountCLP?: number;
  returnUrl?: string;
}

export interface BanchileSessionResponse {
  status: { status: string; reason: string; message: string; date: string };
  requestId: number;
  processUrl: string;
}

/**
 * Crea una sesión de checkout en el sandbox de Banchile Pagos.
 *
 * @returns sessionResponse con requestId y processUrl
 */
export function createSandboxSession(
  opts: CreateSessionOptions = {}
): Promise<BanchileSessionResponse> {
  const auth = buildAuth(SANDBOX_LOGIN, SANDBOX_TRAN_KEY);

  const body = JSON.stringify({
    auth,
    locale: 'es_CL',
    payment: {
      reference: opts.reference ?? `E2E-${Date.now()}`,
      // NOTA: Banchile rechaza caracteres especiales (em-dash, tildes) en description
      description: opts.description ?? 'Test E2E Playwright Banchile integration',
      amount: { currency: 'CLP', total: opts.amountCLP ?? 12000 },
    },
    returnUrl: opts.returnUrl ?? 'http://localhost:8080/return',
    ipAddress: '127.0.0.1',
    userAgent: 'Playwright/E2E Test',
    expiration: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
  });

  const url = new URL(SANDBOX_BASE_URL + '/api/session');

  return new Promise((resolve, reject) => {
    const req = https.request(
      {
        hostname: url.hostname,
        port: 443,
        path: url.pathname,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(body),
        },
      },
      (res) => {
        let data = '';
        res.on('data', (chunk) => (data += chunk));
        res.on('end', () => {
          try {
            const parsed = JSON.parse(data) as BanchileSessionResponse;
            if (!parsed.processUrl) {
              reject(new Error(`Banchile sandbox no retornó processUrl: ${data}`));
            } else {
              resolve(parsed);
            }
          } catch (e) {
            reject(new Error(`No se pudo parsear respuesta Banchile: ${data}`));
          }
        });
      }
    );
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

/**
 * Consulta el estado de una sesión Banchile vía querySession.
 * Útil para verificar el estado final después de un pago de prueba.
 */
export function querySandboxSession(requestId: number): Promise<BanchileSessionResponse> {
  const auth = buildAuth(SANDBOX_LOGIN, SANDBOX_TRAN_KEY);
  const body = JSON.stringify({ auth });
  const url = new URL(`${SANDBOX_BASE_URL}/api/session/${requestId}`);

  return new Promise((resolve, reject) => {
    const req = https.request(
      {
        hostname: url.hostname,
        port: 443,
        path: url.pathname,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(body),
        },
      },
      (res) => {
        let data = '';
        res.on('data', (chunk) => (data += chunk));
        res.on('end', () => {
          try {
            resolve(JSON.parse(data) as BanchileSessionResponse);
          } catch (e) {
            reject(new Error(`No se pudo parsear respuesta Banchile: ${data}`));
          }
        });
      }
    );
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}
