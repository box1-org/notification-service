package org.box1.notification;

import java.util.Map;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** {@link FullStackResource} plus a real MailHog, with {@code notification.provider=mailhog}. */
public class MailHogStackResource extends FullStackResource {
    /** The image the cluster runs (box1-gitops platform/mailhog), by digest. */
    static final String MAILHOG_IMAGE =
            "docker.io/mailhog/mailhog@sha256:8d76a3d4ffa32a3661311944007a415332c4bb855657f4f6c57996405c009bea";
    static GenericContainer<?> mailhog;
    static SmtpRelay relay;

    @Override
    @SuppressWarnings("resource")
    public Map<String, String> start() {
        Map<String, String> config = super.start();
        mailhog = new GenericContainer<>(DockerImageName.parse(MAILHOG_IMAGE)).withExposedPorts(1025, 8025)
                .waitingFor(Wait.forHttp("/api/v2/messages").forPort(8025));
        mailhog.start();
        try {
            relay = new SmtpRelay(mailhog.getHost(), mailhog.getMappedPort(1025));
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
        config.put("notification.provider", "mailhog");
        config.put("notification.smtp.host", "127.0.0.1");
        config.put("notification.smtp.port", String.valueOf(relay.port()));
        config.put("notification.smtp.timeout-millis", "300");
        return config;
    }

    static String api() {
        return "http://" + mailhog.getHost() + ":" + mailhog.getMappedPort(8025);
    }

    @Override
    public void stop() {
        if (relay != null) {
            try {
                relay.close();
            } catch (java.io.IOException ignored) {
                // test teardown
            }
        }
        if (mailhog != null) {
            mailhog.stop();
        }
        super.stop();
    }
}
