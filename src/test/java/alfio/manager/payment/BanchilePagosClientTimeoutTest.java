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

import alfio.manager.payment.BanchilePagosClient.BanchilePagosException;
import alfio.model.transaction.banchile.CreateSessionRequest;
import alfio.model.transaction.banchile.CreateSessionRequest.Amount;
import alfio.model.transaction.banchile.CreateSessionRequest.Auth;
import alfio.model.transaction.banchile.CreateSessionRequest.Payment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Timeout de las llamadas HTTP a Banchile Pagos.
 *
 * <p>El scheduler de Spring tiene un solo hilo: si Banchile se cuelga y la llamada no tiene
 * tope, se detienen todos los jobs (correos, expiración de reservas, stuck-check). Además, el
 * {@code .timeout()} del {@link HttpRequest} no acota un cuerpo de respuesta trabado: solo
 * {@code sendAsync(...).get(N)} acota la llamada completa.
 *
 * <p>Usa un servidor de socket crudo que acepta la request y se cuelga (sin responder, o
 * después de mandar los headers y parte del cuerpo), algo que un servidor HTTP de alto nivel
 * no permite controlar.
 */
class BanchilePagosClientTimeoutTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(500);
    /** Margen para considerar que la llamada volvió "dentro del límite" en una máquina de CI lenta. */
    private static final Duration MAX_EXPECTED_DURATION = Duration.ofSeconds(3);
    /** Tope de seguridad del propio test: sin timeout en el cliente, la llamada quedaría colgada para siempre. */
    private static final Duration TEST_GUARD = Duration.ofSeconds(8);

    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        // mismo protocolo que el HttpClient compartido de producción (DataSourceConfiguration#getHttpClient)
        httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @ParameterizedTest(name = "querySession se rinde a tiempo con Banchile colgado: {0}")
    @EnumSource(StallingServer.Mode.class)
    void querySessionGivesUpWithinTheTimeoutWhenBanchileHangs(StallingServer.Mode mode) throws Exception {
        try (var server = new StallingServer(mode)) {
            var client = new BanchilePagosClient(httpClient, SHORT_TIMEOUT);

            long start = System.nanoTime();
            var exception = assertTimeoutPreemptively(TEST_GUARD, () ->
                assertThrows(BanchilePagosException.class, () -> client.querySession(17412, auth(), server.baseUrl())));
            var elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertTrue(elapsed.compareTo(MAX_EXPECTED_DURATION) < 0,
                () -> "querySession debía rendirse en ~" + SHORT_TIMEOUT.toMillis() + " ms y tardó " + elapsed.toMillis() + " ms");
            assertEquals(-1, exception.getHttpStatus(), "un timeout es un error de comunicación, sin código HTTP");
        }
    }

    @ParameterizedTest(name = "{0} queda acotada por el timeout")
    @EnumSource(Operation.class)
    void everyBanchileOperationIsBoundedByTheTimeout(Operation operation) throws Exception {
        try (var server = new StallingServer(StallingServer.Mode.NO_RESPONSE)) {
            var client = new BanchilePagosClient(httpClient, SHORT_TIMEOUT);

            long start = System.nanoTime();
            assertTimeoutPreemptively(TEST_GUARD, () ->
                assertThrows(BanchilePagosException.class, () -> operation.invoke(client, server.baseUrl())));
            var elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertTrue(elapsed.compareTo(MAX_EXPECTED_DURATION) < 0,
                () -> operation + " debía rendirse en ~" + SHORT_TIMEOUT.toMillis() + " ms y tardó " + elapsed.toMillis() + " ms");
        }
    }

    @ParameterizedTest(name = "el timeout aborta el intercambio HTTP: {0}")
    @EnumSource(StallingServer.Mode.class)
    void timeoutAbortsTheHttpExchange(StallingServer.Mode mode) throws Exception {
        try (var server = new StallingServer(mode)) {
            var client = new BanchilePagosClient(httpClient, SHORT_TIMEOUT);

            assertTimeoutPreemptively(TEST_GUARD, () ->
                assertThrows(BanchilePagosException.class, () -> client.querySession(17412, auth(), server.baseUrl())));

            // si el future no se cancela, la conexión queda abierta esperando el resto de la respuesta
            assertTrue(server.awaitClientClosed(Duration.ofSeconds(3)),
                "después del timeout el cliente debe abortar el intercambio y cerrar la conexión");
        }
    }

    @Test
    void interruptedCallRestoresTheInterruptFlag() throws Exception {
        try (var server = new StallingServer(StallingServer.Mode.NO_RESPONSE)) {
            var client = new BanchilePagosClient(httpClient, Duration.ofSeconds(30));
            var interruptFlagAfterFailure = new CompletableFuture<Boolean>();
            var caller = new Thread(() -> {
                try {
                    client.querySession(17412, auth(), server.baseUrl());
                    interruptFlagAfterFailure.completeExceptionally(new AssertionError("querySession no debía terminar bien"));
                } catch (BanchilePagosException e) {
                    interruptFlagAfterFailure.complete(Thread.currentThread().isInterrupted());
                } catch (RuntimeException e) {
                    interruptFlagAfterFailure.completeExceptionally(e);
                }
            }, "banchile-interrupt-test");
            caller.start();

            assertTrue(server.awaitRequestReceived(Duration.ofSeconds(5)), "la request no llegó al servidor");
            caller.interrupt();

            assertTrue(interruptFlagAfterFailure.get(5, TimeUnit.SECONDS),
                "al interrumpirse, el cliente debe lanzar BanchilePagosException y restaurar el flag de interrupción");
        }
    }

    @Test
    void interruptedCallAbortsTheHttpExchange() throws Exception {
        try (var server = new StallingServer(StallingServer.Mode.NO_RESPONSE)) {
            // tope largo: si el future no se cancela, el intercambio sigue vivo hasta que venza (30 s)
            var client = new BanchilePagosClient(httpClient, Duration.ofSeconds(30));
            var failure = new CompletableFuture<BanchilePagosException>();
            var caller = new Thread(() -> {
                try {
                    client.querySession(17412, auth(), server.baseUrl());
                    failure.completeExceptionally(new AssertionError("querySession no debía terminar bien"));
                } catch (BanchilePagosException e) {
                    failure.complete(e);
                } catch (RuntimeException e) {
                    failure.completeExceptionally(e);
                }
            }, "banchile-interrupt-abort-test");
            caller.start();

            assertTrue(server.awaitRequestReceived(Duration.ofSeconds(5)), "la request no llegó al servidor");
            caller.interrupt();

            assertInstanceOf(InterruptedException.class, failure.get(5, TimeUnit.SECONDS).getCause());
            assertTrue(server.awaitClientClosed(Duration.ofSeconds(3)),
                "al interrumpirse, el cliente debe abortar el intercambio HTTP y cerrar la conexión");
        }
    }

    @Test
    void requestTimeoutIsReportedAsATimeoutAndNotAsAnIoError() {
        // lo que hace el HttpClient cuando vence el .timeout() de la request antes que el get(N)
        var client = new BanchilePagosClient(new RecordingHttpClient(new HttpTimeoutException("request timed out")), SHORT_TIMEOUT);

        var exception = assertThrows(BanchilePagosException.class, () -> client.querySession(17412, auth(), "http://localhost:1"));

        assertEquals("Banchile Pagos no respondió dentro de " + SHORT_TIMEOUT.toMillis() + " ms en querySession", exception.getMessage());
        assertInstanceOf(HttpTimeoutException.class, exception.getCause());
        assertEquals(-1, exception.getHttpStatus());
    }

    @Test
    void connectionErrorIsStillReportedAsAnIoError() {
        var client = new BanchilePagosClient(new RecordingHttpClient(new ConnectException("Connection refused")), SHORT_TIMEOUT);

        var exception = assertThrows(BanchilePagosException.class, () -> client.querySession(17412, auth(), "http://localhost:1"));

        assertEquals("Error de I/O en querySession", exception.getMessage());
        assertInstanceOf(ConnectException.class, exception.getCause());
    }

    @ParameterizedTest(name = "{0} fija .timeout(20 s) en la request")
    @EnumSource(Operation.class)
    void productionClientSetsATwentySecondTimeoutOnEveryRequest(Operation operation) {
        var recordingClient = new RecordingHttpClient();
        var client = new BanchilePagosClient(recordingClient);

        assertThrows(BanchilePagosException.class, () -> operation.invoke(client, "http://localhost:1"));

        var request = recordingClient.lastRequest();
        assertNotNull(request, operation + " no envió ninguna request");
        assertEquals(Optional.of(Duration.ofSeconds(20)), request.timeout(),
            operation + " debe fijar .timeout() en la request");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Auth auth() {
        return BanchilePagosClient.buildAuth("ffb78b93826239e1aa85a515aa961bd9", "U87nG0kcCsjb61Mj");
    }

    /**
     * Las tres llamadas que el cliente hace a Banchile.
     */
    enum Operation {
        CREATE_SESSION {
            @Override
            void invoke(BanchilePagosClient client, String baseUrl) {
                client.createSession(new CreateSessionRequest(auth(), "es_CL", null,
                    new Payment("REF001", "Reserva REF001", new Amount("CLP", 24000L)),
                    "http://localhost/return", "http://localhost/cancel", "http://localhost/webhook",
                    "127.0.0.1", "test", "2026-10-11T00:00:00Z"), baseUrl);
            }
        },
        QUERY_SESSION {
            @Override
            void invoke(BanchilePagosClient client, String baseUrl) {
                client.querySession(17412, auth(), baseUrl);
            }
        },
        CANCEL_SESSION {
            @Override
            void invoke(BanchilePagosClient client, String baseUrl) {
                client.cancelSession(17412, auth(), baseUrl);
            }
        };

        abstract void invoke(BanchilePagosClient client, String baseUrl);
    }

    /**
     * Servidor HTTP/1.1 mínimo sobre un socket crudo: lee la request completa y se cuelga, sin
     * responder ({@link Mode#NO_RESPONSE}) o después de mandar los headers y parte del cuerpo
     * ({@link Mode#PARTIAL_BODY}). Registra cuándo el cliente cierra la conexión.
     */
    static final class StallingServer implements AutoCloseable {

        enum Mode { NO_RESPONSE, PARTIAL_BODY }

        private final Mode mode;
        private final ServerSocket serverSocket;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final CountDownLatch requestReceived = new CountDownLatch(1);
        private final CountDownLatch clientClosed = new CountDownLatch(1);

        StallingServer(Mode mode) throws IOException {
            this.mode = mode;
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            executor.submit(this::acceptConnections);
        }

        String baseUrl() {
            return "http://localhost:" + serverSocket.getLocalPort();
        }

        boolean awaitRequestReceived(Duration timeout) throws InterruptedException {
            return requestReceived.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        boolean awaitClientClosed(Duration timeout) throws InterruptedException {
            return clientClosed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void acceptConnections() {
            while (!serverSocket.isClosed()) {
                try {
                    var socket = serverSocket.accept();
                    sockets.add(socket);
                    executor.submit(() -> stall(socket));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void stall(Socket socket) {
            try (socket) {
                var in = socket.getInputStream();
                readRequest(in);
                requestReceived.countDown();
                if (mode == Mode.PARTIAL_BODY) {
                    var out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n"
                        + "{\"status\": {\"status\": \"APP").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
                // colgado hasta que el cliente cierre la conexión
                while (in.read() != -1) {
                    // descartar
                }
                clientClosed.countDown();
            } catch (IOException e) {
                // reset de la conexión por parte del cliente
                clientClosed.countDown();
            }
        }

        private static void readRequest(InputStream in) throws IOException {
            var headers = new ByteArrayOutputStream();
            int matched = 0;
            byte[] terminator = {'\r', '\n', '\r', '\n'};
            while (matched < terminator.length) {
                int b = in.read();
                if (b == -1) {
                    throw new IOException("conexión cerrada antes de terminar los headers");
                }
                headers.write(b);
                matched = (b == terminator[matched]) ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            int contentLength = headers.toString(StandardCharsets.US_ASCII).lines()
                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                .map(line -> Integer.parseInt(line.substring("content-length:".length()).trim()))
                .findFirst()
                .orElse(0);
            in.readNBytes(contentLength);
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            for (var socket : sockets) {
                socket.close();
            }
            executor.shutdownNow();
        }
    }

    /**
     * {@link HttpClient} que solo registra la última request y falla con la excepción dada, para
     * inspeccionar cómo arma el cliente cada llamada y cómo reporta cada error sin tocar la red.
     */
    private static final class RecordingHttpClient extends HttpClient {

        private final IOException failure;
        private volatile HttpRequest lastRequest;

        RecordingHttpClient() {
            this(new IOException("RecordingHttpClient no hace llamadas reales"));
        }

        RecordingHttpClient(IOException failure) {
            this.failure = failure;
        }

        HttpRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            lastRequest = request;
            throw failure;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            lastRequest = request;
            return CompletableFuture.failedFuture(failure);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }
}
