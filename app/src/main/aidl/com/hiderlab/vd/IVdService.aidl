package com.hiderlab.vd;

import android.view.Surface;

interface IVdService {
    void destroy() = 16777114;
    // "OK:<id> (flags=..)" or "ERR:..."; fps 0 = the phone's own refresh rate
    String createDisplay(int w, int h, int dpi, int fps) = 1;
    String releaseDisplay(int id) = 2;
    String exec(String cmd) = 3;
    // "id,w,h;id,w,h#lastInputError"
    String info() = 5;
    String setSurface(int id, in Surface s) = 6;
    String clearSurface(int id) = 7;
    oneway void touch(int id, int action, float x, float y) = 8;
    oneway void key(int id, int keyCode, int meta) = 9;
    // lines of "taskId|package" for standard tasks on a display
    String tasksOn(int displayId) = 10;
    int rotation(int id) = 11;
    // one raw key event (action = KeyEvent.ACTION_DOWN/UP) into the display
    oneway void keyEvent(int id, int action, int keyCode, int meta) = 12;
    // types text as key events (keeps shift/caps), for the on-screen keyboard
    oneway void typeText(int id, String text) = 13;
}
