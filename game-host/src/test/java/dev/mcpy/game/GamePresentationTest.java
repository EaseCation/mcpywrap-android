package dev.mcpy.game;

import org.junit.Test;
import static org.junit.Assert.*;

public class GamePresentationTest {
    @Test public void missedExitCallbackStillRestoresFullscreen() {
        assertTrue(GamePresentation.fullscreen(false, true, true, false));
    }
    @Test public void neverEnteredPipMustNotRemainPortraitOrHideControls() {
        assertFalse(GamePresentation.fullscreen(false, true, false, false));
        assertTrue(GamePresentation.fullscreen(false, true, false, true));
    }
    @Test public void actualPipAlwaysWinsOverRequestedMode() {
        assertFalse(GamePresentation.fullscreen(true, false, false, true));
        assertTrue(GamePresentation.fullscreen(false, false, false, false));
    }
    @Test public void portraitWindowFitsLandscapeFrameWithoutStretching() {
        assertArrayEquals(new int[]{1220, 560}, GamePresentation.fit(1220, 2656, 2656, 1220));
        assertArrayEquals(new int[]{2656, 1220}, GamePresentation.fit(2656, 1220, 2656, 1220));
        assertArrayEquals(new int[]{764, 351}, GamePresentation.fit(764, 351, 2656, 1220));
    }
}
