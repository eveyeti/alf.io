/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager.payment;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Firma de las notificaciones (webhooks) de Banchile Pagos.
 *
 * <p>Banchile firma cada notificación con {@code SHA-256(requestId + status.status + status.date + secretKey)},
 * con el mismo secretKey de la autenticación ({@code BANCHILE_TRANKEY}), y la manda en el campo
 * {@code signature} con el prefijo {@code sha256:}. La documentación no dice si el digest va en hex o
 * en base64 (su ejemplo, de 40 caracteres, parece un SHA-1 viejo): se aceptan las dos codificaciones
 * del SHA-256, el hex sin importar mayúsculas, con o sin el prefijo. Un SHA-1 nunca se acepta: no
 * se calcula, y sus 20 bytes no pueden igualar los 32 de un SHA-256.
 *
 * <p>La firma recibida se decodifica a bytes y se compara con el digest esperado en tiempo constante
 * ({@link MessageDigest#isEqual}).
 */
public final class BanchileWebhookSignature {

    /** Prefijo con que Banchile manda la firma; se quita antes de comparar, sin importar mayúsculas. */
    static final String PREFIX = "sha256:";
    private static final int SHA256_BYTES = 32;
    private static final int SHA1_BYTES = 20;
    private static final Pattern HEX = Pattern.compile("(?:[0-9a-fA-F]{2})+");
    private static final String SHA1_NOT_ACCEPTED = ": el largo de un SHA-1, que no se acepta";

    private BanchileWebhookSignature() {
    }

    /**
     * Resultado de {@link #verify}.
     *
     * @param valid      true si la firma calza con el SHA-256 esperado, en hex o en base64
     * @param diagnostic para el log: la variante que calzó o, si no calza, la forma de la firma recibida
     *                   y qué variantes se probaron. Nunca incluye el secreto ni el digest esperado
     */
    public record Verification(boolean valid, String diagnostic) {}

    /**
     * Verifica la firma de una notificación de Banchile.
     *
     * @param requestId requestId de la notificación, como texto (el mismo que firma Banchile)
     * @param status    {@code status.status} de la notificación, tal como vino
     * @param date      {@code status.date} de la notificación, tal como vino
     * @param signature campo {@code signature}, con o sin el prefijo {@code sha256:}; puede ser null
     * @param secretKey secretKey del comercio ({@code BANCHILE_TRANKEY})
     * @return si la firma es válida, con el diagnóstico para el log
     */
    public static Verification verify(String requestId, String status, String date, String signature, String secretKey) {
        if (signature == null || signature.isBlank()) {
            return new Verification(false, "sin firma (se espera un SHA-256 en hex o en base64, con o sin el prefijo sha256:)");
        }
        boolean prefixed = signature.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
        String received = (prefixed ? signature.substring(PREFIX.length()) : signature).strip();
        String shape = "prefijo sha256: " + (prefixed ? "presente" : "ausente") + ", " + received.length() + " caracteres";
        if (received.isEmpty()) {
            return new Verification(false, shape + "; firma vacía después del prefijo (se espera un SHA-256 en hex o en base64)");
        }

        byte[] expected = sha256(requestId + status + date + secretKey);
        byte[] asHex = HEX.matcher(received).matches() ? HexFormat.of().parseHex(received) : null;
        byte[] asBase64 = decodeBase64(received);
        boolean hexMatches = asHex != null && asHex.length == SHA256_BYTES && MessageDigest.isEqual(expected, asHex);
        boolean base64Matches = asBase64 != null && asBase64.length == SHA256_BYTES && MessageDigest.isEqual(expected, asBase64);
        if (hexMatches || base64Matches) {
            return new Verification(true, "SHA-256 en " + (hexMatches ? "hex" : "base64") + ", " + shape);
        }
        return new Verification(false, shape + "; variantes probadas: SHA-256 en hex: " + outcome(asHex, received.length() + " caracteres hex")
            + "; SHA-256 en base64: " + outcome(asBase64, "base64 de " + (asBase64 != null ? asBase64.length : 0) + " bytes"));
    }

    /**
     * Cómo le fue a una variante: no coincide (tenía el largo de un SHA-256) o no aplica (no se
     * pudo decodificar, o no tiene 32 bytes; si tiene 20, avisa que es el largo de un SHA-1).
     */
    private static String outcome(byte[] decoded, String decodedShape) {
        if (decoded == null) {
            return "no aplica (no se puede decodificar)";
        }
        if (decoded.length == SHA256_BYTES) {
            return "no coincide";
        }
        return "no aplica (" + decodedShape + (decoded.length == SHA1_BYTES ? SHA1_NOT_ACCEPTED : "; un SHA-256 tiene 32 bytes") + ")";
    }

    private static byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            // no es base64: la variante no aplica, y el diagnóstico lo dice
            return null;
        }
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en este JDK", e);
        }
    }
}
