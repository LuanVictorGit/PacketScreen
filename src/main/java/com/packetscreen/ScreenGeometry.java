package com.packetscreen;

final class ScreenGeometry {

    final double width;
    final double height;
    final int columns;
    final int rows;
    final int contentWidth;
    final int contentHeight;
    final int processingWidth;
    final int processingHeight;
    final int effectiveWidth;
    final int effectiveHeight;
    final int destX;
    final int destY;
    final int destWidth;
    final int destHeight;
    final double cropX;
    final double cropY;
    final double cropWidth;
    final double cropHeight;
    final int sourceWidth;
    final int sourceHeight;

    private ScreenGeometry(double width, double height, int contentWidth, int contentHeight, int processingWidth,
                           int processingHeight, int destX, int destY, int destWidth, int destHeight, double cropX,
                           double cropY, double cropWidth, double cropHeight, int sourceWidth, int sourceHeight) {
        this.width = width;
        this.height = height;
        this.columns = (contentWidth + RenderedFrame.TILE - 1) / RenderedFrame.TILE;
        this.rows = (contentHeight + RenderedFrame.TILE - 1) / RenderedFrame.TILE;
        this.contentWidth = contentWidth;
        this.contentHeight = contentHeight;
        this.processingWidth = processingWidth;
        this.processingHeight = processingHeight;
        this.effectiveWidth = Math.min(processingWidth, contentWidth);
        this.effectiveHeight = Math.min(processingHeight, contentHeight);
        this.destX = destX;
        this.destY = destY;
        this.destWidth = destWidth;
        this.destHeight = destHeight;
        this.cropX = cropX;
        this.cropY = cropY;
        this.cropWidth = cropWidth;
        this.cropHeight = cropHeight;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
    }

    static ScreenGeometry compute(double boxWidth, double boxHeight, boolean preserveAspect, FitMode fitMode,
                                  int requestedWidth, int requestedHeight, int sourceWidth, int sourceHeight,
                                  double autoScale, int maxProcessingWidth, int maxProcessingHeight) {
        boolean source = sourceWidth > 0 && sourceHeight > 0;
        double width = boxWidth;
        double height = boxHeight;
        if (preserveAspect && source) {
            double aspect = sourceWidth / (double) sourceHeight;
            if (width / height > aspect) {
                width = height * aspect;
            } else {
                height = width / aspect;
            }
        }
        int contentWidth = Math.max(1, (int) Math.round(width * RenderedFrame.TILE));
        int contentHeight = Math.max(1, (int) Math.round(height * RenderedFrame.TILE));
        width = contentWidth / (double) RenderedFrame.TILE;
        height = contentHeight / (double) RenderedFrame.TILE;
        int processingWidth;
        int processingHeight;
        if (requestedWidth > 0 && requestedHeight > 0) {
            processingWidth = requestedWidth;
            processingHeight = requestedHeight;
        } else {
            double scaledWidth = Math.max(1, contentWidth * autoScale);
            double scaledHeight = Math.max(1, contentHeight * autoScale);
            double factor = Math.min(1.0, Math.min(maxProcessingWidth / scaledWidth, maxProcessingHeight / scaledHeight));
            processingWidth = Math.max(1, (int) Math.round(scaledWidth * factor));
            processingHeight = Math.max(1, (int) Math.round(scaledHeight * factor));
        }
        int effectiveWidth = Math.min(processingWidth, contentWidth);
        int effectiveHeight = Math.min(processingHeight, contentHeight);
        int destX = 0;
        int destY = 0;
        int destWidth = effectiveWidth;
        int destHeight = effectiveHeight;
        double cropX = 0;
        double cropY = 0;
        double cropWidth = sourceWidth;
        double cropHeight = sourceHeight;
        if (source) {
            double screenAspect = width / height;
            double sourceAspect = sourceWidth / (double) sourceHeight;
            if (fitMode == FitMode.FIT) {
                if (sourceAspect > screenAspect) {
                    destHeight = clamp((int) Math.round(effectiveHeight * screenAspect / sourceAspect), effectiveHeight);
                    destY = (effectiveHeight - destHeight) / 2;
                } else {
                    destWidth = clamp((int) Math.round(effectiveWidth * sourceAspect / screenAspect), effectiveWidth);
                    destX = (effectiveWidth - destWidth) / 2;
                }
            } else if (fitMode == FitMode.FILL) {
                if (sourceAspect > screenAspect) {
                    cropWidth = sourceHeight * screenAspect;
                    cropX = (sourceWidth - cropWidth) / 2.0;
                } else {
                    cropHeight = sourceWidth / screenAspect;
                    cropY = (sourceHeight - cropHeight) / 2.0;
                }
            }
        }
        return new ScreenGeometry(width, height, contentWidth, contentHeight, processingWidth, processingHeight,
                destX, destY, destWidth, destHeight, cropX, cropY, cropWidth, cropHeight, sourceWidth, sourceHeight);
    }

    private static int clamp(int value, int max) {
        return Math.max(1, Math.min(max, value));
    }
}
