package dev.mcpy.game;

/** Evidence from this game process, not from a timeout or a last-screen label. */
public final class WorldExitEvidence {
    public enum Files { OPEN, CLOSED, UNKNOWN }

    private boolean worldSeen;
    private boolean filesSeen;
    private int closedSamples;

    public synchronized boolean observe(boolean worldPresent, Files files) {
        worldSeen |= worldPresent;
        filesSeen |= files == Files.OPEN;
        // A new world can initially report no level and no open files. Neither
        // that nor a failed /proc inspection is evidence of a completed save.
        if (!worldSeen || !filesSeen || worldPresent || files != Files.CLOSED) {
            closedSamples = 0;
            return false;
        }
        return ++closedSamples >= 2;
    }

    public synchronized boolean hasSeenWorld() { return worldSeen; }
}
