package com.padnote.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

public final class OpenAiCancellationTest {
    @Test public void activeCancelInterruptsOwnerDisconnectsAndClearsPoolThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            OpenAiCompatibleClient.Cancellation first =
                    new OpenAiCompatibleClient.Cancellation();
            FakeConnection connection = new FakeConnection();
            CountDownLatch bound = new CountDownLatch(1);
            Future<?> active = pool.submit(() -> {
                try {
                    first.bind(connection);
                    bound.countDown();
                    while (!Thread.currentThread().isInterrupted()) Thread.yield();
                } catch (Exception error) {
                    throw new AssertionError(error);
                } finally {
                    first.unbind(connection);
                }
            });
            assertTrue(bound.await(2, TimeUnit.SECONDS));
            first.cancel();
            active.get(2, TimeUnit.SECONDS);
            assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS));

            Future<?> reused = pool.submit(() -> {
                assertFalse("old cancellation poisoned a reused executor thread",
                        Thread.currentThread().isInterrupted());
                OpenAiCompatibleClient.Cancellation next =
                        new OpenAiCompatibleClient.Cancellation();
                FakeConnection nextConnection;
                try {
                    nextConnection = new FakeConnection();
                    next.bind(nextConnection);
                    next.unbind(nextConnection);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
            reused.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void lateCancelAfterUnbindCannotInterruptNextRequest() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            OpenAiCompatibleClient.Cancellation finished =
                    new OpenAiCompatibleClient.Cancellation();
            FakeConnection oldConnection = new FakeConnection();
            pool.submit(() -> {
                try {
                    finished.bind(oldConnection);
                    finished.unbind(oldConnection);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }).get(2, TimeUnit.SECONDS);

            finished.cancel();
            pool.submit(() -> assertFalse(Thread.currentThread().isInterrupted()))
                    .get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void cancelledBeforeNetworkStartFailsImmediately() throws Exception {
        OpenAiCompatibleClient.Cancellation cancellation =
                new OpenAiCompatibleClient.Cancellation();
        cancellation.cancel();
        assertTrue(cancellation.isCancelled());
        try {
            cancellation.throwIfCancelled();
            fail("cancelled request was allowed to start");
        } catch (OpenAiCompatibleClient.RequestCancelledException expected) {
            assertTrue(expected.getMessage().contains("本机取消"));
        }
    }

    @Test public void cancellingPostUnblocksNetworkAndReturnsCancelledFailure() throws Exception {
        BlockingConnection connection = new BlockingConnection();
        URL url = new URL(null, "https://fixture.invalid/v1/chat/completions",
                new URLStreamHandler() {
                    @Override protected URLConnection openConnection(URL ignored) {
                        return connection;
                    }
                });
        OpenAiCompatibleClient.Cancellation cancellation =
                new OpenAiCompatibleClient.Cancellation();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = pool.submit(() -> {
                try {
                    OpenAiCompatibleClient.post(new AiConfigStore.Config(
                                    "https://fixture.invalid/v1", "model", "token"),
                            url, new JSONObject(), cancellation);
                    return false;
                } catch (OpenAiCompatibleClient.RequestCancelledException expected) {
                    return true;
                }
            });
            assertTrue(connection.responseWaitStarted.await(2, TimeUnit.SECONDS));
            cancellation.cancel();
            assertTrue(result.get(2, TimeUnit.SECONDS));
            assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static class FakeConnection extends HttpsURLConnection {
        final CountDownLatch disconnected = new CountDownLatch(1);

        FakeConnection() throws Exception {
            super(new URL("https://fixture.invalid/v1/chat/completions"));
        }

        @Override public void connect() { }

        @Override public void disconnect() { disconnected.countDown(); }

        @Override public boolean usingProxy() { return false; }

        @Override public String getCipherSuite() { return "fixture"; }

        @Override public java.security.cert.Certificate[] getLocalCertificates() { return null; }

        @Override public java.security.cert.Certificate[] getServerCertificates() { return null; }
    }

    private static final class BlockingConnection extends FakeConnection {
        final CountDownLatch responseWaitStarted = new CountDownLatch(1);

        BlockingConnection() throws Exception { super(); }

        @Override public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override public int getResponseCode() throws IOException {
            responseWaitStarted.countDown();
            try {
                disconnected.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", interrupted);
            }
            throw new IOException("disconnected");
        }
    }
}
