package dev.mcpy.game;

import org.junit.Test;
import static org.junit.Assert.*;
import static dev.mcpy.game.WorldExitEvidence.Files.*;

public class WorldExitEvidenceTest {
    @Test public void startupAndPauseAreNotSaved() {
        WorldExitEvidence state = new WorldExitEvidence();
        assertFalse(state.observe(false, CLOSED));
        assertFalse(state.observe(false, CLOSED));
        assertFalse(state.observe(true, OPEN));
        assertFalse(state.observe(true, OPEN));
        assertFalse(state.observe(true, CLOSED));
    }

    @Test public void worldUnloadWaitsForOutstandingWrites() {
        WorldExitEvidence state = new WorldExitEvidence();
        state.observe(true, OPEN);
        assertFalse(state.observe(false, OPEN));
        assertFalse(state.observe(false, OPEN));
        assertFalse(state.observe(false, CLOSED));
        assertTrue(state.observe(false, CLOSED));
    }

    @Test public void missingOrInterruptedEvidenceCannotConfirmExit() {
        WorldExitEvidence state = new WorldExitEvidence();
        state.observe(true, UNKNOWN);
        assertFalse(state.observe(false, CLOSED));
        assertFalse(state.observe(false, CLOSED));
        state.observe(true, OPEN);
        assertFalse(state.observe(false, CLOSED));
        assertFalse(state.observe(false, UNKNOWN));
        assertFalse(state.observe(false, CLOSED));
        assertTrue(state.observe(false, CLOSED));
    }
}
