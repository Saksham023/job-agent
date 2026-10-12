package io.github.saksham023.jobagent.alerts;

import com.sun.net.httpserver.HttpServer;
import io.github.saksham023.jobagent.alerts.TelegramClient.TelegramException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Telegram client against a tiny local stand-in for api.telegram.org. */
class TelegramClientTest {

    private static final String TOKEN = "123456:SECRET-token";

    private HttpServer server;
    private final AtomicReference<String> next = new AtomicReference<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private TelegramClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            byte[] body = next.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        AlertProperties properties = new AlertProperties(true, 50, 20, 5, Duration.ofMinutes(5), Duration.ofDays(2),
                Duration.ofMinutes(30), "",
                new AlertProperties.Telegram(TOKEN, "JobRadarBot", "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMinutes(10),
                        AlertProperties.Telegram.Mode.POLLING, ""));
        client = new TelegramClient(properties, JsonMapper.builder().build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsAndReadsUpdates() {
        next.set("{\"ok\":true,\"result\":{}}");
        client.sendMessage(42, "hi");
        assertThat(lastPath.get()).isEqualTo("/bot" + TOKEN + "/sendMessage");

        next.set("""
                {"ok":true,"result":[{"update_id":7,"message":{"chat":{"id":42},"text":"/start abc"}},
                                     {"update_id":8,"message":{"chat":{"id":43},"sticker":{}}}]}""");
        assertThat(client.updates(0)).containsExactly(new TelegramClient.Update(7, 42, "/start abc"), new TelegramClient.Update(8, 43, ""));
    }

    @Test
    void registersTheWebhookAndReportsTheConflictPollingGetsAfterwards() {
        next.set("{\"ok\":true,\"result\":true}");
        client.setWebhook("https://jobs.example/api/v1/telegram/webhook", "s3cret");
        assertThat(lastPath.get()).isEqualTo("/bot" + TOKEN + "/setWebhook");

        status.set(409);
        next.set("{\"ok\":false,\"error_code\":409,\"description\":\"Conflict: can't use getUpdates method while webhook is active\"}");
        assertThatThrownBy(() -> client.updates(0))
                .isInstanceOfSatisfying(TelegramException.class, e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void tooManyMessagesSaysHowLongToWait() {
        status.set(429);
        next.set("{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 17\",\"parameters\":{\"retry_after\":17}}");
        assertThatThrownBy(() -> client.sendMessage(42, "hi"))
                .isInstanceOfSatisfying(TelegramException.class, e -> {
                    assertThat(e.status()).isEqualTo(429);
                    assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(17));
                });
    }

    @Test
    void aBlockedBotIs403AndTheTokenNeverAppearsInTheMessage() {
        status.set(403);
        next.set("{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}");
        assertThatThrownBy(() -> client.sendMessage(42, "hi"))
                .isInstanceOfSatisfying(TelegramException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.getMessage()).contains("bot was blocked").doesNotContain(TOKEN);
                });
        assertThat(TelegramClient.hide("I/O error on POST request for https://api.telegram.org/bot" + TOKEN + "/sendMessage", TOKEN))
                .doesNotContain(TOKEN).contains("bot***");
    }
}
