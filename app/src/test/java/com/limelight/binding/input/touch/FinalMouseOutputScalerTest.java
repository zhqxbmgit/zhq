package com.limelight.binding.input.touch;

import static org.junit.Assert.assertEquals;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

public class FinalMouseOutputScalerTest {
    @Test
    public void gainOnePreservesEveryLogicalDelta() {
        FinalMouseOutputScaler scaler = new FinalMouseOutputScaler();
        short[] input = {1, 2, 0, -1, -2};

        for (short logicalDelta : input) {
            assertEquals(logicalDelta, scaler.scaleX(logicalDelta));
        }
    }

    @Test
    public void fractionalGainsPreserveCumulativeDistance() {
        assertEquals(500, sumRepeatedInput(0.5, (short) 1, 1000));
        assertEquals(1250, sumRepeatedInput(1.25, (short) 1, 1000));
        assertEquals(6, sumRepeatedInput(1.5, (short) 1, 4));
        assertEquals(20, sumRepeatedInput(2.0, (short) 1, 10));
        assertEquals(21, sumRepeatedInput(2.1, (short) 1, 10));
    }

    @Test
    public void negativeDirectionIsSymmetric() {
        assertEquals(-6, sumRepeatedInput(1.5, (short) -1, 4));

        for (double gain : new double[]{0.5, 1.25, 1.5, 2.0, 2.1}) {
            assertEquals(
                    Math.abs(sumRepeatedInput(gain, (short) 1, 1000)),
                    Math.abs(sumRepeatedInput(gain, (short) -1, 1000)));
        }
    }

    @Test
    public void alternatingDirectionsDoNotDrift() {
        FinalMouseOutputScaler scaler = new FinalMouseOutputScaler();
        scaler.setGain(1.5);
        int total = 0;

        for (int i = 0; i < 1000; i++) {
            total += scaler.scaleX((short) 1);
            total += scaler.scaleX((short) -1);
        }

        assertEquals(0, total);
    }

    @Test
    public void axesHaveIndependentResiduals() {
        FinalMouseOutputScaler scaler = new FinalMouseOutputScaler();
        scaler.setGain(1.25);

        assertEquals(1, scaler.scaleX((short) 1));
        assertEquals(1, scaler.scaleY((short) 1));
        assertEquals(2, scaler.scaleX((short) 1));
        assertEquals(2, scaler.scaleY((short) 1));
    }

    @Test
    public void zeroInputDoesNotFlushResidual() {
        FinalMouseOutputScaler scaler = new FinalMouseOutputScaler();
        scaler.setGain(1.25);

        assertEquals(1, scaler.scaleX((short) 1));
        assertEquals(0, scaler.scaleX((short) 0));
        assertEquals(2, scaler.scaleX((short) 1));
    }

    @Test
    public void gainChangeResetsResidualWithoutFlushingIt() {
        FinalMouseOutputScaler scaler = new FinalMouseOutputScaler();
        scaler.setGain(1.25);
        assertEquals(1, scaler.scaleX((short) 1));

        scaler.setGain(1.5);

        assertEquals(0, scaler.scaleX((short) 0));
        assertEquals(2, scaler.scaleX((short) 1));
    }

    @Test
    public void resetRestoresGainOneAndClearsResiduals() {
        FinalMouseOutputScaler scaler = new FinalMouseOutputScaler();
        scaler.setGain(1.5);
        assertEquals(2, scaler.scaleX((short) 1));

        scaler.reset();

        assertEquals(1, scaler.scaleX((short) 1));
        assertEquals(-1, scaler.scaleY((short) -1));
    }

    @Test
    public void outputSaturatesInsteadOfWrapping() {
        FinalMouseOutputScaler scaler = new FinalMouseOutputScaler();
        scaler.setGain(3.0);

        assertEquals(Short.MAX_VALUE, scaler.scaleX(Short.MAX_VALUE));
        assertEquals(-3, scaler.scaleX((short) -1));
        assertEquals(Short.MIN_VALUE, scaler.scaleY(Short.MIN_VALUE));
        assertEquals(3, scaler.scaleY((short) 1));
    }

    @Test
    public void preferenceParsingFallsBackOrClampsSafely() {
        assertEquals(1.0, PreferenceConfiguration.parseTrackpadFinalOutputGain(null), 0.0);
        assertEquals(1.0, PreferenceConfiguration.parseTrackpadFinalOutputGain("invalid"), 0.0);
        assertEquals(1.0, PreferenceConfiguration.parseTrackpadFinalOutputGain("NaN"), 0.0);
        assertEquals(1.0, PreferenceConfiguration.parseTrackpadFinalOutputGain("Infinity"), 0.0);
        assertEquals(1.0, PreferenceConfiguration.parseTrackpadFinalOutputGain("0.5"), 0.0);
        assertEquals(1.25, PreferenceConfiguration.parseTrackpadFinalOutputGain("1.25"), 0.0);
        assertEquals(3.0, PreferenceConfiguration.parseTrackpadFinalOutputGain("4.0"), 0.0);
    }

    private static int sumRepeatedInput(double gain, short logicalDelta, int count) {
        FinalMouseOutputScaler scaler = new FinalMouseOutputScaler();
        scaler.setGain(gain);
        int total = 0;
        for (int i = 0; i < count; i++) {
            total += scaler.scaleX(logicalDelta);
        }
        return total;
    }
}
