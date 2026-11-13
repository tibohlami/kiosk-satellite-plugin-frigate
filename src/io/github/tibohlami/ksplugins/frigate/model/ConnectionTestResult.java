// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.model;

/**
 * Outcome of testing connectivity and credentials against a Frigate server.
 */
public final class ConnectionTestResult {
    public final boolean ok;
    public final String message;
    public final int cameraCount;

    private ConnectionTestResult(boolean ok, String message, int cameraCount) {
        this.ok = ok;
        this.message = message;
        this.cameraCount = cameraCount;
    }

    public static ConnectionTestResult success(String message, int cameraCount) {
        return new ConnectionTestResult(true, message, cameraCount);
    }

    public static ConnectionTestResult error(String message) {
        return new ConnectionTestResult(false, message, 0);
    }
}
