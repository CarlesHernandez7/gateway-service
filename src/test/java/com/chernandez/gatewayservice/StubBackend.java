package com.chernandez.gatewayservice;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal stand-in for user-service whose behaviour each test chooses. */
final class StubBackend implements AutoCloseable {

    @FunctionalInterface
    interface Behavior {
        /** @param hit 1-based number of the request being served */
        void handle(HttpExchange exchange, int hit) throws Exception;
    }

    static final Behavior OK = (exchange, hit) -> respond(exchange, 200, "ok");

    static Behavior slow(long millis) {
        return (exchange, hit) -> {
            Thread.sleep(millis);
            respond(exchange, 200, "ok");
        };
    }

    static Behavior status(int status) {
        return (exchange, hit) -> respond(exchange, status, "status " + status);
    }

    /** Closes the connection without answering the first {@code count} requests, then behaves normally. */
    static Behavior dropFirst(int count) {
        return (exchange, hit) -> {
            if (hit <= count) {
                exchange.close();
            } else {
                respond(exchange, 200, "ok");
            }
        };
    }

    private final HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile Behavior behavior = OK;

    StubBackend() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            int hit = hits.incrementAndGet();
            try {
                behavior.handle(exchange, hit);
            } catch (Exception ex) {
                exchange.close();
            }
        });
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    int hits() {
        return hits.get();
    }

    /** Switches behaviour and resets the request counter. */
    void use(Behavior newBehavior) {
        behavior = newBehavior;
        hits.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
