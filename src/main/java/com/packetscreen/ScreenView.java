package com.packetscreen;

final class ScreenView {

    final ScreenLayout layout;
    final RenderedFrame frame;
    final BackFace back;
    final ScreenLayout[] turns;

    ScreenView(ScreenLayout layout, RenderedFrame frame, BackFace back, ScreenLayout[] turns) {
        this.layout = layout;
        this.frame = frame;
        this.back = back;
        this.turns = turns;
    }
}
