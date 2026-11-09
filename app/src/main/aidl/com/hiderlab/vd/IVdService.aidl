package com.hiderlab.vd;

interface IVdService {
    void destroy() = 16777114;
    String createDisplay(int w, int h, int dpi) = 1;
    String releaseDisplay() = 2;
    String exec(String cmd) = 3;
    int currentDisplay() = 4;
}
