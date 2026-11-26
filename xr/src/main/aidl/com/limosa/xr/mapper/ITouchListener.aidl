package com.limosa.xr.mapper;

/** Raw touches from the grabbed touchscreen, sent from the Shizuku service to the app. */
oneway interface ITouchListener {
    /**
     * One input frame: count fingers, ids[i] stable per finger, xy[2i], xy[2i+1] normalized
     * 0..1 in the panel's natural (unrotated) orientation.
     */
    void onFrame(in int[] ids, in float[] xy, int count);
    void onStopped(String reason);
}
