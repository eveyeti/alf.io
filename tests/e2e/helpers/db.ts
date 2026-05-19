/**
 * Helper de base de datos para los E2E tests de Banchile.
 * Conecta directo al postgres del mirror local via puerto 5433.
 *
 * SOLO para uso en tests locales — NUNCA conectar a producción.
 */
import { Client } from 'pg';
import { randomUUID } from 'crypto';

const MIRROR_DB = {
  host: 'localhost',
  port: 5433,
  database: 'alfio_prod',
  user: 'alfio',
  // Set via env var ALFIO_MIRROR_DB_PASSWORD (ver docker-compose del mirror)
  // Valor por defecto en el mirror local de desarrollo
  password: process.env.ALFIO_MIRROR_DB_PASSWORD ?? 'RF91poFpkygsmEZZ7PY2JfXBaGQLkozr',
};

export function createDbClient(): Client {
  return new Client(MIRROR_DB);
}

/**
 * Crea una reserva de prueba en estado PENDING con payment_method=BANCHILE.
 *
 * La reserva es mínima pero válida para que Alfio la procese:
 * - estado: EXTERNAL_PROCESSING_PAYMENT (equivale a "Banchile sesión creada")
 * - payment_method: BANCHILE (para que el UI muestre el estado correcto)
 *
 * Retorna el reservationId para navegar a la página de resumen.
 *
 * @param client  Cliente pg conectado
 * @param eventId ID del evento (default: 5 = tributo_soda)
 */
export async function createTestReservation(
  client: Client,
  {
    eventId = 5,
    status = 'PENDING',
    paymentMethod = 'BANCHILE',
    firstName = 'Test',
    lastName = 'Playwright',
    email = `playwright-test-${Date.now()}@banchile-e2e.local`,
    srcPriceCts = 12000, // 1 ticket a $12.000 CLP
  } = {}
): Promise<string> {
  const reservationId = randomUUID();
  const now = new Date().toISOString();
  // Validez: 30 minutos desde ahora
  const validity = new Date(Date.now() + 30 * 60 * 1000).toISOString();

  await client.query(
    `INSERT INTO tickets_reservation (
       id, validity, status, full_name, first_name, last_name, email_address,
       payment_method, user_language, direct_assignment,
       src_price_cts, event_id_fk, creation_ts, automatic
     ) VALUES (
       $1, $2, $3, $4, $5, $6, $7,
       $8, 'es', false,
       $9, $10, $11, false
     )`,
    [
      reservationId,
      validity,
      status,
      `${firstName} ${lastName}`,
      firstName,
      lastName,
      email,
      paymentMethod,
      srcPriceCts,
      eventId,
      now,
    ]
  );

  console.log(`[db] Reserva de prueba creada: ${reservationId} (status=${status})`);
  return reservationId;
}

/**
 * Busca una transacción de Banchile para una reserva dada.
 * Retorna la fila de b_transaction o null.
 */
export async function findTransaction(
  client: Client,
  reservationId: string
): Promise<Record<string, unknown> | null> {
  const res = await client.query(
    `SELECT id, gtw_tx_id, gtw_payment_id, reservation_id, status, payment_proxy, t_timestamp
     FROM b_transaction
     WHERE reservation_id = $1
     ORDER BY t_timestamp DESC
     LIMIT 1`,
    [reservationId]
  );
  return res.rows[0] ?? null;
}

/**
 * Actualiza el status de una reserva (para reset entre tests).
 */
export async function updateReservationStatus(
  client: Client,
  reservationId: string,
  newStatus: string
): Promise<void> {
  await client.query(
    `UPDATE tickets_reservation SET status = $1 WHERE id = $2`,
    [newStatus, reservationId]
  );
}

/**
 * Elimina una reserva de prueba y sus transacciones asociadas.
 * Solo elimina reservas que tengan email que termine en @banchile-e2e.local
 * (como capa de seguridad para no borrar datos reales).
 */
export async function cleanupTestReservation(
  client: Client,
  reservationId: string
): Promise<void> {
  // Verificar que sea de prueba
  const res = await client.query(
    `SELECT email_address FROM tickets_reservation WHERE id = $1`,
    [reservationId]
  );
  if (res.rows.length === 0) return;

  const email: string = res.rows[0].email_address ?? '';
  if (!email.endsWith('@banchile-e2e.local')) {
    console.warn(`[db] WARN: No borrando reserva ${reservationId} — email no es de prueba: ${email}`);
    return;
  }

  await client.query(`DELETE FROM b_transaction WHERE reservation_id = $1`, [reservationId]);
  await client.query(`DELETE FROM tickets_reservation WHERE id = $1`, [reservationId]);
  console.log(`[db] Reserva de prueba eliminada: ${reservationId}`);
}
