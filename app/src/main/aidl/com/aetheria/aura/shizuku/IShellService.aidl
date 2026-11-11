package com.aetheria.aura.shizuku;

interface IShellService {
    /**
     * Reserved by the Shizuku server: called to kill this user service.
     * Transaction code is fixed by the Shizuku protocol (see Shizuku.UserServiceArgs docs).
     */
    void destroy() = 16777114;

    /** Explicit exit (client-initiated). */
    void exit() = 1;

    /**
     * Run a shell command as uid 2000 (shell).
     * Returns "<exitCode>\n<base64 stdout>\n<base64 stderr>". Never throws for command failure.
     */
    String exec(String command, int timeoutSeconds);

    /** Read up to 512KB from a file the shell uid can read (e.g. /data/local/tmp). */
    byte[] readChunk(String path, int offset, int length);

    /**
     * uiautomator dump, parsed server-side.
     * Returns lines "text\tresource-id\tclickable\tbounds" (bounds "[x1,y1][x2,y2]"),
     * or "ERR: ..." on failure.
     */
    String uidump(int maxNodes);
}
