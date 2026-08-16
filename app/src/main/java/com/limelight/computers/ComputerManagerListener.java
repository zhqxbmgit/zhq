package com.limelight.computers;

import com.limelight.nvstream.http.ComputerDetails;

public interface ComputerManagerListener {
    void notifyComputerUpdated(ComputerDetails details);

    default void notifyComputerServerInfoUnavailable(String computerUuid,
                                                     long observedAt) {
    }

    default void notifyComputerServerInfoAvailable(String computerUuid,
                                                   long observedAt) {
    }
}
