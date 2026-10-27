package party.jeoparody.game;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Turns on WebSockets (@EnableWebSocket) and maps GameSocketHandler to /ws. Spring's default origin check
 * stays on: only pages from this same site may connect. Behind Cloud Run that check works because
 * application-cloud.yml sets server.forward-headers-strategy=framework (the original https:// is trusted).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameSocketHandler handler;

    /** The one handler for every screen. */
    public WebSocketConfig(GameSocketHandler handler) {
        this.handler = handler;
    }

    /** The URL the browsers open: wss://<site>/ws?game=CODE&role=... */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws");
    }

    /** Drawings are sent as PNG data URLs, which are far bigger than the 8 KB default message size. */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(512 * 1024);
        return container;
    }
}
