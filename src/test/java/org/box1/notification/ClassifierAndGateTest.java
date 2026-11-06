package org.box1.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Optional;

import org.box1.notification.delivery.Classifier;
import org.box1.notification.delivery.FaultGate;
import org.box1.notification.delivery.ProviderResult;
import org.box1.notification.domain.FailureCode;
import org.junit.jupiter.api.Test;

class ClassifierAndGateTest {
    @Test
    void permanentFourHundredsAreNotRetriedButTransientOnesAre() {
        assertEquals(Classifier.Outcome.TRANSIENT, Classifier.classify(ProviderResult.status(503)));
        assertEquals(Classifier.Outcome.TRANSIENT, Classifier.classify(ProviderResult.status(500)));
        assertEquals(Classifier.Outcome.TRANSIENT, Classifier.classify(ProviderResult.status(429)));
        assertEquals(Classifier.Outcome.TRANSIENT, Classifier.classify(ProviderResult.status(408)));
        assertEquals(Classifier.Outcome.TRANSIENT, Classifier.classify(ProviderResult.timeout()));
        assertEquals(Classifier.Outcome.TRANSIENT, Classifier.classify(ProviderResult.error()));
        assertEquals(Classifier.Outcome.PERMANENT, Classifier.classify(ProviderResult.status(422)));
        assertEquals(Classifier.Outcome.PERMANENT, Classifier.classify(ProviderResult.status(400)));
        assertEquals(Classifier.Outcome.SUCCESS, Classifier.classify(ProviderResult.accepted("rcpt_x")));
        assertEquals(FailureCode.PROVIDER_UNAVAILABLE, Classifier.code(ProviderResult.status(503)));
        assertEquals(FailureCode.PROVIDER_REJECTED, Classifier.code(ProviderResult.status(422)));
        assertEquals(FailureCode.PROVIDER_TIMEOUT, Classifier.code(ProviderResult.timeout()));
    }

    @Test
    void gateOpensOnlyWhenEnabledAndTheDownwardApiNamespaceIsAllowlisted() {
        assertEquals("FAULTS_DISABLED", new FaultGate(false, Optional.of("demo"), Optional.of(List.of("demo"))).refusal());
        assertEquals("NAMESPACE_UNKNOWN", new FaultGate(true, Optional.empty(), Optional.of(List.of("demo"))).refusal());
        assertEquals("NAMESPACE_UNKNOWN", new FaultGate(true, Optional.of(" "), Optional.of(List.of("demo"))).refusal());
        assertEquals("NAMESPACE_NOT_ALLOWLISTED",
                new FaultGate(true, Optional.of("notification-service-dev"), Optional.of(List.of("demo"))).refusal());
        assertEquals("NAMESPACE_NOT_ALLOWLISTED", new FaultGate(true, Optional.of("demo"), Optional.empty()).refusal());
        assertNull(new FaultGate(true, Optional.of("demo"), Optional.of(List.of(" demo "))).refusal());
    }
}
