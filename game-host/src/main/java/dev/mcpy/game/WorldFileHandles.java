package dev.mcpy.game;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;

/** Read-only inspection of the current process's handles for its own world. */
public final class WorldFileHandles {
    private WorldFileHandles() {}

    public static WorldExitEvidence.Files inspect(File world) {
        try {
            String canonical = world.getCanonicalPath() + "/";
            // Shared storage has /storage and /mnt aliases. Match the complete
            // workspace-relative world path rather than one mount's spelling.
            int workspace = canonical.indexOf("/mcpy/worlds/");
            if (workspace < 0) return WorldExitEvidence.Files.UNKNOWN;
            String suffix = canonical.substring(workspace);
            File[] handles = new File("/proc/self/fd").listFiles();
            if (handles == null || handles.length == 0) return WorldExitEvidence.Files.UNKNOWN;
            boolean unknown = false;
            for (File handle : handles) {
                try {
                    String path = Os.readlink(handle.getPath());
                    if (path.contains(suffix)) return WorldExitEvidence.Files.OPEN;
                } catch (ErrnoException error) {
                    // Handles can close between listing and readlink. A denied
                    // inspection, in contrast, must never prove a saved world.
                    if (error.errno != OsConstants.ENOENT) unknown = true;
                }
            }
            return unknown ? WorldExitEvidence.Files.UNKNOWN : WorldExitEvidence.Files.CLOSED;
        } catch (Exception error) {
            return WorldExitEvidence.Files.UNKNOWN;
        }
    }
}
