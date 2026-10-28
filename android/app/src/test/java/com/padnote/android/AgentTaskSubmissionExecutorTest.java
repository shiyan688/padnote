package com.padnote.android;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class AgentTaskSubmissionExecutorTest {
    @Test public void sameClientIdIsInFlightOnlyOnce() throws Exception {
        String id="padnote-test-inflight";
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        assertTrue(AgentTaskSubmissionExecutor.execute(id,()->{
            calls.incrementAndGet();entered.countDown();
            try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }));
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        assertFalse(AgentTaskSubmissionExecutor.execute(id,calls::incrementAndGet));
        assertTrue(AgentTaskSubmissionExecutor.isInFlight(id));
        release.countDown();
        long deadline=System.currentTimeMillis()+2000;
        while(AgentTaskSubmissionExecutor.isInFlight(id)&&System.currentTimeMillis()<deadline)Thread.sleep(10);
        assertEquals(1,calls.get());
        assertFalse(AgentTaskSubmissionExecutor.isInFlight(id));
    }
}
