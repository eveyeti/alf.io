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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Firma de las notificaciones de Banchile: {@code SHA-256(requestId + status.status + status.date + secretKey)},
 * con el prefijo {@code sha256:} opcional y el digest en hex (sin importar mayúsculas) o en base64.
 * Las firmas esperadas se calculan acá, aparte del código de producción.
 */
class BanchileWebhookSignatureTest {

    private static final String SECRET_KEY = "U87nG0kcCsjb61Mj";
    private static final String REQUEST_ID = "71001";
    private static final String STATUS = "APPROVED";
    private static final String DATE = "2026-10-14T10:15:30-03:00";

    @Test
    void acceptsTheHexDigestWithThePrefix() {
        assertTrue(verify("sha256:" + hex(sha256(REQUEST_ID + STATUS + DATE + SECRET_KEY))).valid());
    }

    @Test
    void acceptsTheHexDigestInUpperCase() {
        assertTrue(verify("sha256:" + hex(sha256(REQUEST_ID + STATUS + DATE + SECRET_KEY)).toUpperCase(Locale.ROOT)).valid());
    }

    @Test
    void acceptsTheHexDigestWithoutThePrefix() {
        assertTrue(verify(hex(sha256(REQUEST_ID + STATUS + DATE + SECRET_KEY))).valid());
    }

    @Test
    void acceptsTheBase64DigestWithThePrefix() {
        assertTrue(verify("sha256:" + base64(sha256(REQUEST_ID + STATUS + DATE + SECRET_KEY))).valid());
    }

    @Test
    void acceptsTheBase64DigestWithoutThePrefix() {
        assertTrue(verify(base64(sha256(REQUEST_ID + STATUS + DATE + SECRET_KEY))).valid());
    }

    @Test
    void acceptsThePrefixInUpperCase() {
        assertTrue(verify("SHA256:" + hex(sha256(REQUEST_ID + STATUS + DATE + SECRET_KEY))).valid());
    }

    /**
     * Vector conocido, con el secretKey del sandbox público de Banchile: el mismo ejemplo que sirve
     * para probar el webhook a mano con curl.
     */
    @Test
    void acceptsAKnownVectorCalculatedWithShasum() {
        String hex = "b6610f86afffad772572f7206d596bb788cc39d7c3c21257f494fbd0c0c59695";
        String base64 = "tmEPhq//rXclcvcgbVlrt4jMOdfDwhJX9JT70MDFlpU=";

        assertTrue(BanchileWebhookSignature.verify("12345", "APPROVED", "2026-10-14T10:15:30-03:00", "sha256:" + hex, SECRET_KEY).valid());
        assertTrue(BanchileWebhookSignature.verify("12345", "APPROVED", "2026-10-14T10:15:30-03:00", "sha256:" + base64, SECRET_KEY).valid());
    }

    @Test
    void rejectsADigestCalculatedWithAnotherSecret() {
        assertFalse(verify("sha256:" + hex(sha256(REQUEST_ID + STATUS + DATE + "otro-secreto"))).valid());
    }

    /**
     * La firma ata el requestId, el estado y la fecha: cambiar cualquiera la invalida.
     */
    @Test
    void rejectsTheSignatureOfAnotherStatusDateOrRequestId() {
        assertFalse(verify("sha256:" + hex(sha256(REQUEST_ID + "REJECTED" + DATE + SECRET_KEY))).valid());
        assertFalse(verify("sha256:" + hex(sha256(REQUEST_ID + STATUS + "2026-10-14T10:15:31-03:00" + SECRET_KEY))).valid());
        assertFalse(verify("sha256:" + hex(sha256("71002" + STATUS + DATE + SECRET_KEY))).valid());
    }

    /**
     * El ejemplo de la documentación de Banchile tiene 40 caracteres, el largo de un SHA-1 en hex: un
     * SHA-1 de la misma cadena, con el secreto correcto, no se acepta.
     */
    @Test
    void rejectsASha1DigestOfTheSameInputInHexOrBase64() {
        byte[] sha1 = digest("SHA-1", REQUEST_ID + STATUS + DATE + SECRET_KEY);
        var asHex = verify("sha256:" + hex(sha1));
        var asBase64 = verify("sha256:" + base64(sha1));

        assertFalse(asHex.valid());
        assertFalse(asBase64.valid());
        assertTrue(asHex.diagnostic().contains("SHA-1"), asHex.diagnostic());
        assertTrue(asBase64.diagnostic().contains("SHA-1"), asBase64.diagnostic());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "sha256:", "sha256:   ", "firma-incorrecta", "sha256:zzzz", "sha256:@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@"})
    void rejectsMissingOrMalformedSignatures(String signature) {
        assertFalse(verify(signature).valid());
    }

    /**
     * El diagnóstico va al log cuando la firma no calza: dice qué variantes se probaron, pero nunca
     * incluye el secreto ni el digest esperado.
     */
    @Test
    void theDiagnosticSaysWhichVariantsWereTriedWithoutTheSecretOrTheExpectedDigest() {
        byte[] expected = sha256(REQUEST_ID + STATUS + DATE + SECRET_KEY);
        var wrongHex = verify("sha256:" + hex(sha256(REQUEST_ID + STATUS + DATE + "otro-secreto")));
        var wrongBase64 = verify(base64(sha256(REQUEST_ID + STATUS + DATE + "otro-secreto")));

        assertTrue(wrongHex.diagnostic().contains("hex"), wrongHex.diagnostic());
        assertTrue(wrongHex.diagnostic().contains("base64"), wrongHex.diagnostic());
        assertTrue(wrongHex.diagnostic().contains("prefijo"), wrongHex.diagnostic());
        for (var verification : java.util.List.of(wrongHex, wrongBase64, verify(null), verify("firma-incorrecta"))) {
            var diagnostic = verification.diagnostic();
            assertFalse(diagnostic.contains(SECRET_KEY), diagnostic);
            assertFalse(diagnostic.toLowerCase(Locale.ROOT).contains(hex(expected)), diagnostic);
            assertFalse(diagnostic.contains(base64(expected)), diagnostic);
        }
    }

    private static BanchileWebhookSignature.Verification verify(String signature) {
        return BanchileWebhookSignature.verify(REQUEST_ID, STATUS, DATE, signature, SECRET_KEY);
    }

    private static byte[] sha256(String input) {
        return digest("SHA-256", input);
    }

    private static byte[] digest(String algorithm, String input) {
        try {
            return MessageDigest.getInstance(algorithm).digest(input.getBytes(UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
