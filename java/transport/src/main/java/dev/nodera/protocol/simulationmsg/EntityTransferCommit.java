package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/** Final joint certificate and paired deltas broadcast to both region committees. */
public record EntityTransferCommit(
        EntityTransferCertificate certificate,
        QuorumCertificate sourceActionCertificate,
        RegionDelta sourceDelta,
        RegionDelta targetDelta
) implements NoderaMessage {

    public EntityTransferCommit {
        Objects.requireNonNull(certificate, "certificate");
        Objects.requireNonNull(sourceActionCertificate, "sourceActionCertificate");
        Objects.requireNonNull(sourceDelta, "sourceDelta");
        Objects.requireNonNull(targetDelta, "targetDelta");
        if (!certificate.descriptor().sourceRegion().equals(sourceDelta.region())
                || !certificate.descriptor().targetRegion().equals(targetDelta.region())) {
            throw new IllegalArgumentException("transfer deltas do not match certificate regions");
        }
        if (!sourceActionCertificate.region().equals(sourceDelta.region())
                || !sourceActionCertificate.version().equals(sourceDelta.baseVersion())
                || !sourceActionCertificate.resultingRoot().equals(sourceDelta.resultingRoot())) {
            throw new IllegalArgumentException("source action certificate does not match source delta");
        }
    }
}
