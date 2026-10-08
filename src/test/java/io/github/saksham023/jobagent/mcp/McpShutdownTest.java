package io.github.saksham023.jobagent.mcp;

import io.github.saksham023.jobagent.common.ShutdownSignal;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The MCP sessions are closed on stop, after the crawls wind down and before Tomcat's graceful shutdown. */
class McpShutdownTest {

    /** Tomcat's graceful shutdown phase (WebServerGracefulShutdownLifecycle: SmartLifecycle.DEFAULT_PHASE - 2048). */
    private static final int WEB_SERVER_GRACEFUL_SHUTDOWN_PHASE = Integer.MAX_VALUE - 2048;

    @Test
    @SuppressWarnings("unchecked")
    void stopClosesTheMcpServer() {
        McpSyncServer server = mock(McpSyncServer.class);
        ObjectProvider<McpSyncServer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(server);
        McpShutdown shutdown = new McpShutdown(provider);
        shutdown.start();

        shutdown.stop();

        verify(server).closeGracefully();
        assertThat(shutdown.isRunning()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void stopsAfterTheCrawlsAndBeforeTheWebServer() {
        McpShutdown shutdown = new McpShutdown(mock(ObjectProvider.class));
        assertThat(shutdown.getPhase()).isLessThan(new ShutdownSignal().getPhase())
                .isGreaterThan(WEB_SERVER_GRACEFUL_SHUTDOWN_PHASE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutAnMcpServerStopDoesNothing() {
        ObjectProvider<McpSyncServer> provider = mock(ObjectProvider.class);
        new McpShutdown(provider).stop();
        verify(provider).getIfAvailable();
    }
}
