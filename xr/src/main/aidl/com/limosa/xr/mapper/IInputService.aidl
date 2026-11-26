package com.limosa.xr.mapper;

import com.limosa.xr.mapper.ITouchListener;

/** Runs as the shell user through Shizuku: owns the touchscreen and injects touches. */
interface IInputService {
    /** Shizuku calls this when the service is unbound for good. */
    void destroy() = 16777114;

    /** Takes over the touchscreen and streams its touches to the listener. Returns an error, or null. */
    String start(ITouchListener listener) = 1;

    /** Gives the touchscreen back. */
    void stop() = 2;

    /**
     * Sets the fingers that apps should see: count pointers, ids[i] (0..31), xy in display
     * pixels of the current rotation. Pointers missing from the last call are lifted.
     */
    void inject(in int[] ids, in float[] xy, int count) = 3;
}
