package dev.nodera.core.crypto;

import dev.nodera.core.Bytes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HashService}. Bakes in official NIST SHA-256 golden vectors and verifies
 * the consensus {@code hash(Encodable)} path.
 */
class HashServiceTest {

    /** Official NIST SHA-256 of the empty string. */
    private static final String GOLDEN_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    /** Official NIST SHA-256 of "abc". */
    private static final String GOLDEN_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    /** Trivial Encodable: u16 STATE_ROOT tag + u16 version + a fixed string. */
    private static final class TestEncodable implements Encodable {
        static final String PAYLOAD = "nodera-canonical";

        @Override
        public void encode(CanonicalWriter w) {
            w.writeU16(TypeTags.STATE_ROOT).writeU16(ENCODING_VERSION);
            w.writeString(PAYLOAD);
        }
    }

    @Test
    void sha256OfEmptyMatchesNistGoldenVector() {
        HashService svc = new HashService();
        assertThat(svc.sha256(new byte[0]).toHex()).isEqualTo(GOLDEN_EMPTY);
    }

    @Test
    void sha256OfAbcMatchesNistGoldenVector() {
        HashService svc = new HashService();
        byte[] abc = "abc".getBytes(StandardCharsets.UTF_8);
        assertThat(svc.sha256(abc).toHex()).isEqualTo(GOLDEN_ABC);
    }

    @Test
    void sha256BytesReturnsFresh32ByteArray() {
        HashService svc = new HashService();
        byte[] out = svc.sha256Bytes("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(out).hasSize(32);
        byte[] out2 = svc.sha256Bytes("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(out2).isNotSameAs(out);
    }

    @Test
    void hashOfEncodableReturns32Bytes() {
        HashService svc = new HashService();
        Bytes h = svc.hash(new TestEncodable());
        assertThat(h.length()).isEqualTo(32);
    }

    @Test
    void hashOfEncodableIsDeterministic() {
        HashService svc = new HashService();
        Bytes a = svc.hash(new TestEncodable());
        Bytes b = svc.hash(new TestEncodable());
        assertThat(a).isEqualTo(b);
    }

    @Test
    void sha256IsThreadSafeAcrossConcurrentCalls() throws Exception {
        HashService svc = new HashService();
        byte[] data = "nodera-thread-safety-smoke".getBytes(StandardCharsets.UTF_8);
        Bytes singleThread = svc.sha256(data);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicReference<Bytes> r1 = new AtomicReference<>();
            AtomicReference<Bytes> r2 = new AtomicReference<>();

            pool.submit(() -> hashOnce(svc, data, r1, ready, start, done));
            pool.submit(() -> hashOnce(svc, data, r2, ready, start, done));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(r1.get()).isEqualTo(singleThread);
            assertThat(r2.get()).isEqualTo(singleThread);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void hashOnce(HashService svc, byte[] data, AtomicReference<Bytes> sink,
                                 CountDownLatch ready, CountDownLatch start, CountDownLatch done) {
        ready.countDown();
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        sink.set(svc.sha256(data));
        done.countDown();
    }
}
