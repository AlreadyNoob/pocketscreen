package com.hiderlab.vd;

import android.view.Surface;

interface IVdService {
    void destroy() = 16777114;
    String createDisplay(int w, int h, int dpi) = 1;
    String releaseDisplay() = 2;
    String exec(String cmd) = 3;
    int currentDisplay() = 4;
    // "id,width,height,lastInputError"
    String info() = 5;
    // Show the hidden display inside the viewer's SurfaceView
    String setSurface(in Surface s) = 6;
    // Go back to the invisible (ImageReader) surface
    String clearSurface() = 7;
    oneway void touch(int action, float x, float y) = 8;
    oneway void key(int keyCode, int meta) = 9;
    // lines of "taskId|package" for standard tasks on a display
    String tasksOn(int displayId) = 10;
    // Surface.ROTATION_* of the hidden display
    int rotation() = 11;
}
