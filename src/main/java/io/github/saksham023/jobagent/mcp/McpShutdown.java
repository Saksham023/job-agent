package io.github.saksham023.jobagent.mcp;

import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Closes the MCP sessions when the app stops, BEFORE the web server's graceful shutdown.
 *
 * An MCP client (Claude Code, Claude Desktop) keeps one HTTP request open per session: the event stream on which the
 * server could push messages. Tomcat's graceful shutdown waits for every open request to end, and such a stream
 * never ends by itself, so one connected client held every shutdown for the whole lifecycle timeout (90 s) and the
 * deploy script had to kill the app. Closing the server first ends those streams; the clients simply reconnect to
 * the new app.
 *
 * Phase: lifecycle beans stop from the highest phase down. ShutdownSignal (Integer.MAX_VALUE) stops first and lets the
 * crawls wind down; this bean comes next; Tomcat's graceful shutdown (Integer.MAX_VALUE - 2048) comes after it.
 */
@Component
public class McpShutdown implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(McpShutdown.class);

    private final ObjectProvider<McpSyncServer> server;
    private volatile boolean running;

    public McpShutdown(ObjectProvider<McpSyncServer> server) {
        this.server = server;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        McpSyncServer mcp = server.getIfAvailable();
        if (mcp == null) {
            return;
        }
        try {
            mcp.closeGracefully();
            log.info("Shutting down: MCP sessions closed");
        } catch (RuntimeException e) {
            log.warn("Shutting down: closing the MCP sessions failed: {}", e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1024;
    }
}
