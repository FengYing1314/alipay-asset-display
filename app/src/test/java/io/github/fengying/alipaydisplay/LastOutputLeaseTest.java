package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class LastOutputLeaseTest {
    @Test
    public void delayedUnmarkedCopyBlocksRepeatedAdmission() {
        LastOutputLease lease = new LastOutputLease();
        lease.confirmOwned("9,999.99");
        assertTrue(lease.rejectsUnowned("9,999.99"));
        assertTrue(lease.isBlocked());
        assertFalse(lease.acceptHost("9,999.99", false));
        assertTrue(lease.rejectsUnowned("9,999.99"));
    }

    @Test
    public void reliableDifferentHostValueReleasesTheBarrier() {
        LastOutputLease lease = new LastOutputLease();
        lease.confirmOwned("9,999.99");
        lease.rejectsUnowned("9,999.99");
        assertTrue(lease.acceptHost("12.00", false));
        assertFalse(lease.isBlocked());
        assertFalse(lease.rejectsUnowned("9,999.99"));
    }

    @Test
    public void explicitBindingProofAllowsGenuineSameValue() {
        LastOutputLease lease = new LastOutputLease();
        lease.confirmOwned("9,999.99");
        lease.rejectsUnowned("9,999.99");
        assertTrue(lease.acceptHost("9,999.99", true));
        assertFalse(lease.isBlocked());
        assertFalse(lease.rejectsUnowned("9,999.99"));
    }

    @Test
    public void onlyLastOutputIsRetainedAndFieldCloseClearsIt() {
        LastOutputLease lease = new LastOutputLease();
        lease.confirmOwned("1.00");
        lease.confirmOwned("2.00");
        assertFalse(lease.rejectsUnowned("1.00"));
        assertTrue(lease.rejectsUnowned("2.00"));
        lease.clear();
        assertFalse(lease.isBlocked());
        assertFalse(lease.rejectsUnowned("2.00"));
    }

    @Test
    public void ambiguousCopyNeverReplacesTrustedHostSource() {
        LastOutputLease lease = new LastOutputLease();
        DisplayValueState<String> source = new DisplayValueState<>("12.00", null, true, false);
        lease.confirmOwned("9,999.99");
        boolean visible = !lease.rejectsUnowned("9,999.99");
        source.hostText(visible ? "9,999.99" : null, visible);
        assertFalse(source.canRender());
        assertNull(source.hostText());
        assertTrue(lease.rejectsUnowned("9,999.99"));
    }

    @Test
    public void visibleBindingOwnershipChangeProvesGenuineSameAmount() {
        Object before = new Object(), after = new Object();
        LastOutputLease lease = new LastOutputLease();
        lease.confirmOwned("9,999.99");
        lease.rejectsUnowned("9,999.99");
        BindingTextProof proof = new BindingTextProof(before, true, true, 3, 5);
        assertTrue(proof.changedToHostAmount(after, true, false, 3, 5));
        assertTrue(lease.acceptHost("9,999.99", true));
        assertFalse(lease.isBlocked());
    }

    @Test
    public void unchangedVisibleBindingDoesNotUnlockAnEcho() {
        Object sameText = new Object();
        LastOutputLease lease = new LastOutputLease();
        lease.confirmOwned("9,999.99");
        lease.rejectsUnowned("9,999.99");
        BindingTextProof proof = new BindingTextProof(sameText, true, false, 3, 5);
        assertFalse(proof.changedToHostAmount(sameText, true, false, 3, 5));
        assertTrue(lease.isBlocked());
        assertFalse(lease.acceptHost("9,999.99", false));
    }
}
