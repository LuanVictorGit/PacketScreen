package com.packetscreen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScreenGeometryTest {

    private static ScreenGeometry image(double width, double height, boolean preserve, FitMode mode, int sourceWidth, int sourceHeight) {
        return ScreenGeometry.compute(width, height, preserve, mode, 0, 0, sourceWidth, sourceHeight, 1.0, 1280, 1280);
    }

    @Test
    void preservedAspectKeepsFractionalSize() {
        ScreenGeometry geometry = image(4, 3, true, FitMode.FIT, 1920, 1080);
        assertEquals(4.0, geometry.width, 1e-9);
        assertEquals(2.25, geometry.height, 1e-9);
        assertEquals(512, geometry.contentWidth);
        assertEquals(288, geometry.contentHeight);
        assertEquals(4, geometry.columns);
        assertEquals(3, geometry.rows);
        assertEquals(512, geometry.effectiveWidth);
        assertEquals(288, geometry.effectiveHeight);
        assertEquals(0, geometry.destX);
        assertEquals(0, geometry.destY);
        assertEquals(512, geometry.destWidth);
        assertEquals(288, geometry.destHeight);
    }

    @Test
    void fitLetterboxesWideSourceOnTallerScreen() {
        ScreenGeometry geometry = image(4, 3, false, FitMode.FIT, 1920, 1080);
        assertEquals(512, geometry.contentWidth);
        assertEquals(384, geometry.contentHeight);
        assertEquals(512, geometry.destWidth);
        assertEquals(288, geometry.destHeight);
        assertEquals(48, geometry.destY);
        assertEquals(0, geometry.cropX, 1e-9);
        assertEquals(1920, geometry.cropWidth, 1e-9);
    }

    @Test
    void fillCropsTheExcessAndCoversEverything() {
        ScreenGeometry geometry = image(4, 3, false, FitMode.FILL, 1920, 1080);
        assertEquals(0, geometry.destX);
        assertEquals(0, geometry.destY);
        assertEquals(512, geometry.destWidth);
        assertEquals(384, geometry.destHeight);
        assertEquals(1440, geometry.cropWidth, 1e-6);
        assertEquals(240, geometry.cropX, 1e-6);
        assertEquals(1080, geometry.cropHeight, 1e-6);
    }

    @Test
    void stretchUsesTheWholeSourceAndScreen() {
        ScreenGeometry geometry = image(4, 3, false, FitMode.STRETCH, 1920, 1080);
        assertEquals(512, geometry.destWidth);
        assertEquals(384, geometry.destHeight);
        assertEquals(0, geometry.cropX, 1e-9);
        assertEquals(1920, geometry.cropWidth, 1e-9);
        assertEquals(1080, geometry.cropHeight, 1e-9);
    }

    @Test
    void squareSourceOnRectangularScreen() {
        ScreenGeometry geometry = image(8, 4.5, false, FitMode.FIT, 1000, 1000);
        assertEquals(1024, geometry.contentWidth);
        assertEquals(576, geometry.contentHeight);
        assertEquals(576, geometry.destWidth);
        assertEquals(224, geometry.destX);
        assertEquals(576, geometry.destHeight);
    }

    @Test
    void verticalVideoOnHorizontalScreen() {
        ScreenGeometry geometry = ScreenGeometry.compute(8, 4.5, false, FitMode.FIT, 0, 0, 1080, 1920, 1.0, 2048, 2048);
        assertEquals(324, geometry.destWidth);
        assertEquals(350, geometry.destX);
        assertEquals(576, geometry.destHeight);
    }

    @Test
    void processingResolutionDoesNotChangeVirtualSize() {
        ScreenGeometry automatic = ScreenGeometry.compute(8, 4.5, false, FitMode.FIT, 0, 0, 1280, 720, 1.0, 2048, 2048);
        ScreenGeometry manual = ScreenGeometry.compute(8, 4.5, false, FitMode.FIT, 256, 144, 1280, 720, 1.0, 2048, 2048);
        assertEquals(automatic.width, manual.width, 1e-9);
        assertEquals(automatic.height, manual.height, 1e-9);
        assertEquals(automatic.columns, manual.columns);
        assertEquals(256, manual.effectiveWidth);
        assertEquals(144, manual.effectiveHeight);
        assertEquals(1024, automatic.effectiveWidth);
    }

    @Test
    void processingAboveTheMapResolutionIsClamped() {
        ScreenGeometry geometry = ScreenGeometry.compute(1, 1, false, FitMode.STRETCH, 2048, 2048, 100, 100, 1.0, 4096, 4096);
        assertEquals(2048, geometry.processingWidth);
        assertEquals(128, geometry.effectiveWidth);
        assertEquals(128, geometry.effectiveHeight);
    }

    @Test
    void videoAutomaticResolutionUsesScale() {
        ScreenGeometry geometry = ScreenGeometry.compute(8, 4.5, true, FitMode.FIT, 0, 0, 1280, 720, 0.5, 1280, 1280);
        assertEquals(512, geometry.processingWidth);
        assertEquals(288, geometry.processingHeight);
    }

    @Test
    void automaticResolutionRespectsTheConfiguredMaximum() {
        ScreenGeometry geometry = ScreenGeometry.compute(10, 10, false, FitMode.STRETCH, 0, 0, 0, 0, 1.0, 512, 512);
        assertEquals(512, geometry.processingWidth);
        assertEquals(512, geometry.processingHeight);
        assertEquals(10, geometry.columns);
    }

    @Test
    void unknownSourceFillsTheContentArea() {
        ScreenGeometry geometry = image(3, 2, true, FitMode.FIT, 0, 0);
        assertEquals(384, geometry.contentWidth);
        assertEquals(256, geometry.contentHeight);
        assertEquals(384, geometry.destWidth);
        assertEquals(256, geometry.destHeight);
    }
}
