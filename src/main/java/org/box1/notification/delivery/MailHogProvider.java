package org.box1.notification.delivery;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.box1.notification.store.StubStore;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Sends each notice as a plain-text SMTP message to MailHog, the in-cluster sink. An SMTP 250 for the message is
 * provider acceptance: the receipt is written with {@code provider=mailhog}. {@code DELIVERED} therefore means
 * accepted by MailHog, not read by a person; nothing leaves the cluster.
 *
 * <p>Outcomes map onto the provider-neutral result: connection failures and timeouts are transient; an SMTP 4xx reply
 * is recorded as 503 (transient, {@code PROVIDER_UNAVAILABLE}); an SMTP 5xx reply as 422 (permanent,
 * {@code PROVIDER_REJECTED}). No SMTP server text is kept.
 */
@ApplicationScoped
public class MailHogProvider implements DeliveryProvider {
    public static final String NAME = "mailhog";
    private static final Logger LOG = Logger.getLogger(MailHogProvider.class);
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.-]{1,253}$");
    private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9-]{1,63}(\\.[a-z0-9-]{1,63})+$");
    private static final Pattern ADDRESS = Pattern.compile("^[a-z0-9._-]{1,64}@[a-z0-9-]{1,63}(\\.[a-z0-9-]{1,63})+$");

    private final StubStore store;
    private final String host;
    private final int port;
    private final String from;
    private final String recipientDomain;
    private final int timeoutMillis;

    public MailHogProvider(StubStore store,
            @ConfigProperty(name = "notification.smtp.host") String host,
            @ConfigProperty(name = "notification.smtp.port") int port,
            @ConfigProperty(name = "notification.smtp.from") String from,
            @ConfigProperty(name = "notification.smtp.recipient-domain") String recipientDomain,
            @ConfigProperty(name = "notification.smtp.timeout-millis") int timeoutMillis) {
        this.store = store;
        this.host = host;
        this.port = port;
        this.from = from;
        this.recipientDomain = recipientDomain;
        this.timeoutMillis = timeoutMillis;
    }

    /** Checked only when this provider is selected, so the stub runs without SMTP settings. */
    MailHogProvider validated() {
        if (host == null || !HOST.matcher(host).matches() || port < 1 || port > 65535) {
            throw new IllegalStateException("notification.smtp.host and port must name the MailHog SMTP service");
        }
        if (from == null || !ADDRESS.matcher(from).matches()) {
            throw new IllegalStateException("notification.smtp.from must be a plain address such as box1-notifications@box1.local");
        }
        if (recipientDomain == null || !DOMAIN.matcher(recipientDomain).matches()) {
            throw new IllegalStateException("notification.smtp.recipient-domain must be a domain such as box1.local");
        }
        if (timeoutMillis < 100 || timeoutMillis > 30000) {
            throw new IllegalStateException("notification.smtp.timeout-millis must be between 100 and 30000");
        }
        LOG.infov("MailHog provider: smtp={0}:{1} recipients=<channel>@{2}", host, String.valueOf(port), recipientDomain);
        return this;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ProviderResult deliver(Delivery delivery) {
        OperatorMail.Rendered mail = OperatorMail.render(delivery, recipientDomain);
        try {
            Transport.send(message(mail));
        } catch (MessagingException error) {
            ProviderResult result = classify(error);
            LOG.infov("SMTP send not accepted: result={0} status={1}", result.kind(), result.httpStatus());
            return result;
        }
        byte[] bytes = delivery.body().getBytes(StandardCharsets.UTF_8);
        String bodySha = StubProvider.sha256(bytes);
        String written = store.writeReceipt(new StubStore.NewReceipt(StubProvider.receiptId(delivery, bodySha),
                delivery.notificationId(), NAME, delivery.channel(), bodySha, bytes.length, mail.messageId(), mail.recipient()));
        if (written == null) {
            // Accepted by MailHog after the notice became terminal (a late call); the message exists but is not counted.
            LOG.warn("MailHog accepted a message for a notice that is already terminal");
            return ProviderResult.status(409);
        }
        return ProviderResult.accepted(written);
    }

    private MimeMessage message(OperatorMail.Rendered mail) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "false");
        props.put("mail.smtp.starttls.enable", "false");
        props.put("mail.smtp.localhost", "notification-service.box1.local");
        props.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMillis));
        props.put("mail.smtp.timeout", String.valueOf(timeoutMillis));
        props.put("mail.smtp.writetimeout", String.valueOf(timeoutMillis));
        MimeMessage message = new FixedIdMessage(Session.getInstance(props), mail.messageId());
        message.setFrom(new InternetAddress(from, true));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(mail.recipient(), true));
        message.setSubject(mail.subject(), "UTF-8");
        message.setSentDate(new Date());
        message.setText(mail.text(), "UTF-8");
        for (Map.Entry<String, String> header : mail.headers().entrySet()) {
            message.setHeader(header.getKey(), header.getValue());
        }
        return message;
    }

    static ProviderResult classify(MessagingException error) {
        Integer code = smtpCode(error);
        if (code != null) {
            return code >= 500 ? ProviderResult.status(422) : ProviderResult.status(503);
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return ProviderResult.timeout();
            }
            if (cause instanceof ConnectException) {
                return ProviderResult.error();
            }
        }
        return ProviderResult.error();
    }

    /** The SMTP reply code of a refused send, if the server sent one. */
    static Integer smtpCode(MessagingException error) {
        Exception current = error;
        while (current != null) {
            if (current instanceof SMTPSendFailedException failed && failed.getReturnCode() >= 400) {
                return failed.getReturnCode();
            }
            if (current instanceof SMTPAddressFailedException failed && failed.getReturnCode() >= 400) {
                return failed.getReturnCode();
            }
            current = current instanceof MessagingException messaging ? messaging.getNextException() : null;
        }
        return null;
    }

    /** Keeps the Message-ID derived from the notification ID instead of a random one. */
    private static final class FixedIdMessage extends MimeMessage {
        private final String messageId;

        FixedIdMessage(Session session, String messageId) {
            super(session);
            this.messageId = messageId;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", messageId);
        }
    }
}
