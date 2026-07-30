package dev.nodera.mod.common;

import dev.nodera.transport.TransportException;
import org.junit.jupiter.api.Test;

import java.net.BindException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #39: {@link NoderaPeerService#isBindFailure} decides whether a host-transport start failure
 * is a port collision (worth an ephemeral-port retry) or something else (degrade only). It must walk
 * the cause chain, because the bind failure arrives either bare (the direct-socket path) or wrapped
 * twice (the rendezvous path: {@code TransportException("failed to start rendezvous transport",
 * TransportException("failed to bind ...", BindException))}). Pure + static, so MC-free.
 */
final class NoderaPeerServiceIsBindFailureTest {

    @Test
    void recognisesABareBindException() {
        assertTrue(NoderaPeerService.isBindFailure(new BindException("Address already in use")));
    }

    @Test
    void recognisesTheDirectSocketBindPath() {
        // SocketPeerTransport.start: the shape that crashed client-one.
        Exception e = new TransportException("failed to bind 0.0.0.0:25566", new BindException());
        assertTrue(NoderaPeerService.isBindFailure(e));
    }

    @Test
    void recognisesTheRendezvousWrappedBindPath() {
        // composeHostTransport -> rendezvous.start() -> directTransport.start() throws; the bind
        // failure is two causes deep under "failed to start rendezvous transport".
        Exception bind = new TransportException("failed to bind 0.0.0.0:25566", new BindException());
        Exception wrapped = new TransportException("failed to start rendezvous transport", bind);
        assertTrue(NoderaPeerService.isBindFailure(wrapped));
    }

    @Test
    void recognisesTheMessageFormWithoutACause() {
        assertTrue(NoderaPeerService.isBindFailure(
                new TransportException("Address already in use")));
    }

    @Test
    void ignoresUnrelatedFailures() {
        assertFalse(NoderaPeerService.isBindFailure(new IllegalArgumentException("bad endpoint")));
        assertFalse(NoderaPeerService.isBindFailure(new TransportException("discovery failed")));
        assertFalse(NoderaPeerService.isBindFailure(new RuntimeException("tracker timeout")));
    }

    @Test
    void isNullSafe() {
        assertFalse(NoderaPeerService.isBindFailure(null));
    }
}
