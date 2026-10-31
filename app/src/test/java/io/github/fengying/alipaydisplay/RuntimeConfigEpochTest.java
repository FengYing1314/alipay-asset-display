package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class RuntimeConfigEpochTest {
    @Test
    public void deduplicatedRequestsStillAdvanceTheEpochCapturedAtReadStart() {
        ConfigReadEpoch epoch = new ConfigReadEpoch();
        assertFalse(epoch.pending());
        long first = epoch.request();
        long second = epoch.request();
        assertEquals(first + 1, second);
        assertEquals(second, epoch.current());
        assertTrue(epoch.pending());
        assertTrue(epoch.publish(second));
        assertFalse(epoch.pending());
    }

    @Test
    public void aRequestDuringAnOlderReadPreventsItsSnapshotFromPublishing() {
        ConfigReadEpoch epoch = new ConfigReadEpoch();
        long reading = epoch.request();
        long newer = epoch.request();
        assertFalse(epoch.publish(reading));
        assertTrue(epoch.pending());
        assertFalse(epoch.publish(newer + 1));
        assertTrue(epoch.publish(newer));
        assertFalse(epoch.pending());
    }

    @Test
    public void oldMainThreadCallbackCannotReplaceAnAlreadyPublishedNewSnapshot() {
        ConfigReadEpoch epoch = new ConfigReadEpoch();
        long old = epoch.request();
        long latest = epoch.request();
        assertTrue(epoch.publish(latest));
        assertFalse(epoch.publish(old));
        assertFalse(epoch.pending());
        assertEquals(latest, epoch.current());
    }

    @Test
    public void aRequestAfterPublicationReopensTheFenceUntilItsOwnSnapshotArrives() {
        ConfigReadEpoch epoch = new ConfigReadEpoch();
        long first = epoch.request();
        assertTrue(epoch.publish(first));
        assertFalse(epoch.pending());
        long second = epoch.request();
        assertTrue(epoch.pending());
        assertFalse(epoch.publish(first));
        assertTrue(epoch.pending());
        assertTrue(epoch.publish(second));
        assertFalse(epoch.pending());
    }
}
