package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

public class BindingTextProofTest {
    @Test
    public void changedHostObjectInsideVisibleBindingIsFresh() {
        Object before = new Object(), after = new Object();
        BindingTextProof proof = new BindingTextProof(before, true, false, 3, 5);
        assertTrue(proof.changedToHostAmount(after, true, false, 3, 5));
    }

    @Test
    public void unchangedOldAmountIsNotFresh() {
        Object before = new Object();
        BindingTextProof proof = new BindingTextProof(before, true, false, 3, 5);
        assertFalse(proof.changedToHostAmount(before, true, false, 3, 5));
    }

    @Test
    public void maskOrModuleOwnershipTransitionCanProveInPlaceUpdate() {
        Object mutableText = new Object();
        assertTrue(
                new BindingTextProof(mutableText, false, false, 3, 5)
                        .changedToHostAmount(mutableText, true, false, 3, 5));
        assertTrue(
                new BindingTextProof(mutableText, true, true, 3, 5)
                        .changedToHostAmount(mutableText, true, false, 3, 5));
    }

    @Test
    public void hiddenEpochChangeOrAlreadyObservedHookInvalidatesFallback() {
        BindingTextProof proof = new BindingTextProof(new Object(), false, false, 3, 5);
        assertFalse(proof.changedToHostAmount(new Object(), true, false, 4, 5));
        assertFalse(proof.changedToHostAmount(new Object(), true, false, 3, 6));
    }

    @Test
    public void masksAndModuleProducedTextCannotBecomeHostSources() {
        BindingTextProof proof = new BindingTextProof(new Object(), false, false, 3, 5);
        assertFalse(proof.changedToHostAmount(new Object(), false, false, 3, 5));
        assertFalse(proof.changedToHostAmount(new Object(), true, true, 3, 5));
    }
}
