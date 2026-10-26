package dev.mcpy.game;

/** Window policy independent of whether an OEM delivered a PiP callback. */
public final class GamePresentation {
    private GamePresentation() {}

    public static boolean fullscreen(boolean actualPip, boolean directPip,
                                     boolean pipSeen, boolean initialAttemptFinished) {
        return !actualPip && (!directPip || pipSeen || initialAttemptFinished);
    }

    public static int[] fit(int width, int height, int renderWidth, int renderHeight) {
        if (width <= 0 || height <= 0 || renderWidth <= 0 || renderHeight <= 0) return new int[]{0, 0};
        double scale = Math.min((double) width / renderWidth, (double) height / renderHeight);
        return new int[]{Math.max(1, (int) Math.round(renderWidth * scale)),
                         Math.max(1, (int) Math.round(renderHeight * scale))};
    }
}
