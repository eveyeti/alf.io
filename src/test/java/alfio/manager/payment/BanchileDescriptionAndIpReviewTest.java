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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests del revisor (ronda 2, rama revision-v5-r2): fuzzing de {@code descriptionText} contra la
 * lista de caracteres que el sandbox de Banchile aceptó, y bordes de {@code isPublicIpAddress}.
 * Llaman a los métodos privados por reflexión para recorrer millones de entradas sin crear sesiones.
 */
class BanchileDescriptionAndIpReviewTest {

    /** Lo que puede salir: letras ASCII, dígitos, espacio, los símbolos aceptados y las letras del castellano. */
    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9 ,.:;\\-_'()/#&%+@áéíóúñüÁÉÍÓÚÑÜ]*$");

    private static Method descriptionText;
    private static Method isIpLiteral;
    private static Method isPublicIpAddress;

    @BeforeAll
    static void reflect() throws Exception {
        descriptionText = BanchilePagosWebhookManager.class.getDeclaredMethod("descriptionText", String.class);
        isIpLiteral = BanchilePagosWebhookManager.class.getDeclaredMethod("isIpLiteral", String.class);
        isPublicIpAddress = BanchilePagosWebhookManager.class.getDeclaredMethod("isPublicIpAddress", String.class);
        descriptionText.setAccessible(true);
        isIpLiteral.setAccessible(true);
        isPublicIpAddress.setAccessible(true);
    }

    // -------------------------------------------------------------------------
    // descriptionText
    // -------------------------------------------------------------------------

    /**
     * Cada code point de Unicode (también los sustitutos sueltos), solo y entre dos letras, deja una
     * descripción con caracteres de la lista, sin espacios repetidos ni en los extremos.
     */
    @Test
    void reviewerEveryCodePointGivesOnlyAllowedCharacters() throws Exception {
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            String s = new String(Character.toChars(cp));
            assertAllowed(s);
            assertAllowed("a" + s + "b");
        }
    }

    /**
     * Cada letra ASCII seguida de una o dos marcas combinantes (U+0300 a U+036F): la NFC puede
     * componerlas en una letra con diacrítico, que tiene que salir de la lista o pasar a su base.
     */
    @Test
    void reviewerAsciiLettersWithCombiningMarksGiveOnlyAllowedCharacters() throws Exception {
        for (char base = 'A'; base <= 'z'; base++) {
            if (!Character.isLetter(base)) {
                continue;
            }
            for (char m1 = '̀'; m1 <= 'ͯ'; m1++) {
                assertAllowed("" + base + m1);
                for (char m2 = '̀'; m2 <= 'ͯ'; m2++) {
                    assertAllowed("" + base + m1 + m2);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Straße", "ﬁesta", "Ǆ", "İstanbul ı", "Москва", "Αθήνα", "東京", "ＡＢＣ１２３", "٣٤٥ ۶۷", "a b​cㅤd",
        "é́", "Ω K Å", "𝐁𝐢𝐧𝐠𝐨", "N° 1 º ª", "&#191; &lt;b&gt;"})
    void reviewerUnusualEventNamesGiveOnlyAllowedCharacters(String name) throws Exception {
        assertAllowed(name);
    }

    private static void assertAllowed(String in) throws Exception {
        String out = (String) descriptionText.invoke(null, in);
        if (!ALLOWED.matcher(out).matches() || out.contains("  ") || !out.equals(out.strip())) {
            fail("entrada " + codePoints(in) + " -> " + codePoints(out) + " \"" + out + "\"");
        }
    }

    private static String codePoints(String s) {
        var sb = new StringBuilder();
        s.codePoints().forEach(c -> sb.append(String.format("U+%04X ", c)));
        return sb.toString().strip();
    }

    // -------------------------------------------------------------------------
    // isPublicIpAddress
    // -------------------------------------------------------------------------

    private static boolean sent(String ip) throws Exception {
        return (boolean) isIpLiteral.invoke(null, ip) && (boolean) isPublicIpAddress.invoke(null, ip);
    }

    /** Bordes de los rangos que no son públicos: no se mandan. */
    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "0.255.255.255", "10.0.0.0", "100.64.0.0", "100.127.255.255", "127.255.255.255",
        "169.254.0.0", "169.254.255.255", "172.16.0.0", "172.31.255.255", "192.168.0.0", "192.168.255.255",
        "::", "::1", "::0.0.0.1", "fc00::", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fe80::",
        "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "::ffff:0.0.0.0", "::ffff:100.64.0.1", "::ffff:169.254.0.1"})
    void reviewerNonPublicBordersAreNotSent(String ip) throws Exception {
        assertFalse(sent(ip), ip);
    }

    /** Justo afuera de esos rangos: se mandan. */
    @ParameterizedTest
    @ValueSource(strings = {"1.0.0.0", "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255",
        "128.0.0.0", "169.253.255.255", "169.255.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0",
        "fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fe00::1", "fe7f:ffff::1"})
    void reviewerPublicBordersAreSent(String ip) throws Exception {
        assertTrue(sent(ip), ip);
    }

    /**
     * Documenta lo que hoy se manda aunque no puede ser la IP de un cliente real: multicast,
     * reservadas y broadcast, documentación y benchmark, IPv4-compatibles ({@code ::a.b.c.d}, incluida
     * {@code ::127.0.0.1}), 6to4 y NAT64 con una IPv4 privada adentro, site-local {@code fec0::/10}.
     * Solo llegan con un {@code Forwarded} falso o un proxy mal configurado.
     */
    @ParameterizedTest
    @ValueSource(strings = {"224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255", "192.0.0.1", "192.0.2.1",
        "198.18.0.1", "198.51.100.1", "203.0.113.1", "::127.0.0.1", "::10.0.0.1", "::192.168.1.1", "2002:a00:1::1",
        "2002:c0a8:101::1", "64:ff9b::a00:1", "64:ff9b::7f00:1", "ff02::1", "2001:db8::1", "fec0::1", "100::1"})
    void reviewerTheseNonClientAddressesAreStillSent(String ip) throws Exception {
        assertTrue(sent(ip), ip);
    }

    /**
     * Ningún literal que pase {@code isIpLiteral} hace que {@code isPublicIpAddress} lance ni tarde
     * (InetAddress no consulta el DNS con un literal): 400.000 candidatos al azar con forma de IPv6,
     * con y sin IPv4 embebida, octetos de más de 255 y ceros a la izquierda.
     */
    @Test
    void reviewerNoIpLiteralThrowsOrLooksUpTheDns() throws Exception {
        var random = new Random(20261015L);
        var hex = "0123456789abcdefABCDEF";
        int accepted = 0;
        long slowest = 0;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < 400_000; i++) {
            var sb = new StringBuilder();
            if (random.nextInt(10) == 0) {
                sb.append(random.nextBoolean() ? ":" : ".");
            }
            int groups = random.nextInt(9);
            int doubleColonAt = random.nextInt(groups + 2) - 1;
            for (int g = 0; g < groups; g++) {
                if (g == doubleColonAt) {
                    sb.append("::");
                } else if (g > 0) {
                    sb.append(':');
                }
                int len = 1 + random.nextInt(4);
                for (int k = 0; k < len; k++) {
                    sb.append(hex.charAt(random.nextInt(hex.length())));
                }
            }
            if (doubleColonAt == groups) {
                sb.append("::");
            }
            if (random.nextInt(3) == 0) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
                    sb.append(':');
                }
                for (int o = 0; o < 4; o++) {
                    if (o > 0) {
                        sb.append('.');
                    }
                    int octet = random.nextInt(300);
                    sb.append(random.nextInt(8) == 0 ? String.format("%03d", octet) : String.valueOf(octet));
                }
            }
            String candidate = sb.toString();
            if (!(boolean) isIpLiteral.invoke(null, candidate)) {
                continue;
            }
            accepted++;
            if (candidate.startsWith(".")) {
                failures.add(candidate + " empieza con punto: InetAddress lo resolvería por DNS");
            }
            long start = System.nanoTime();
            try {
                isPublicIpAddress.invoke(null, candidate);
            } catch (InvocationTargetException e) {
                failures.add(candidate + " -> " + e.getCause());
            }
            slowest = Math.max(slowest, System.nanoTime() - start);
        }
        assertTrue(accepted > 1000, "pocos candidatos válidos: " + accepted);
        assertTrue(failures.isEmpty(), failures.toString());
        assertTrue(slowest < 200_000_000L, "una llamada tardó " + slowest / 1_000_000 + " ms");
    }
}
