package com.limelight.binding.input.touch;

final class FinalMouseOutputScaler {
    private static final double DEFAULT_GAIN = 1.0;

    private double gain = DEFAULT_GAIN;
    private double outputResidualX;
    private double outputResidualY;

    void setGain(double gain) {
        double safeGain = Double.isFinite(gain) ? gain : DEFAULT_GAIN;
        if (Double.compare(this.gain, safeGain) != 0) {
            this.gain = safeGain;
            resetResiduals();
        }
    }

    short scaleX(short logicalDelta) {
        if (logicalDelta == 0 || gain == DEFAULT_GAIN) {
            return logicalDelta;
        }

        double scaled = logicalDelta * gain + outputResidualX;
        double rounded = roundSymmetrically(scaled);
        if (rounded > Short.MAX_VALUE) {
            outputResidualX = 0;
            return Short.MAX_VALUE;
        }
        if (rounded < Short.MIN_VALUE) {
            outputResidualX = 0;
            return Short.MIN_VALUE;
        }

        outputResidualX = scaled - rounded;
        return (short) rounded;
    }

    short scaleY(short logicalDelta) {
        if (logicalDelta == 0 || gain == DEFAULT_GAIN) {
            return logicalDelta;
        }

        double scaled = logicalDelta * gain + outputResidualY;
        double rounded = roundSymmetrically(scaled);
        if (rounded > Short.MAX_VALUE) {
            outputResidualY = 0;
            return Short.MAX_VALUE;
        }
        if (rounded < Short.MIN_VALUE) {
            outputResidualY = 0;
            return Short.MIN_VALUE;
        }

        outputResidualY = scaled - rounded;
        return (short) rounded;
    }

    void reset() {
        gain = DEFAULT_GAIN;
        resetResiduals();
    }

    private void resetResiduals() {
        outputResidualX = 0;
        outputResidualY = 0;
    }

    private static double roundSymmetrically(double value) {
        return value >= 0 ? Math.floor(value + 0.5) : Math.ceil(value - 0.5);
    }
}
