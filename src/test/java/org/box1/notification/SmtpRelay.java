package org.box1.notification;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A TCP relay between the service and MailHog. While black-holed it accepts connections and never answers, which is
 * what a hung SMTP server looks like; otherwise it forwards bytes both ways.
 */
final class SmtpRelay implements AutoCloseable {
    private final ServerSocket server;
    private final String targetHost;
    private final int targetPort;
    private final AtomicBoolean blackhole = new AtomicBoolean();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();

    SmtpRelay(String targetHost, int targetPort) throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        Thread.ofVirtual().start(this::acceptLoop);
    }

    int port() {
        return server.getLocalPort();
    }

    void blackhole(boolean on) {
        blackhole.set(on);
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                sockets.add(client);
                if (blackhole.get()) {
                    continue; // held open, never answered
                }
                Socket upstream = new Socket(targetHost, targetPort);
                sockets.add(upstream);
                pipe(client.getInputStream(), upstream.getOutputStream());
                pipe(upstream.getInputStream(), client.getOutputStream());
            } catch (IOException error) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private static void pipe(InputStream in, OutputStream out) {
        Thread.ofVirtual().start(() -> {
            try (in; out) {
                in.transferTo(out);
            } catch (IOException ignored) {
                // connection closed
            }
        });
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket socket : sockets) {
            socket.close();
        }
    }
}
