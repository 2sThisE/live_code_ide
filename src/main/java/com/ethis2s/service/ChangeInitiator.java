package com.ethis2s.service;

/**
 * Represents the initiator of a text change in the CodeArea.
 * This helps distinguish between changes made by the user,
 * changes coming from the server, and internal system changes.
 */
public enum ChangeInitiator {
    /**
     * Change initiated by the local user (e.g., typing, pasting).
     */
    USER,

    /**
     * Change initiated by a remote user, received from the server.
     */
    SERVER,

    /**
     * Change initiated by the system (e.g., initial file loading, auto-formatting).
     */
    SYSTEM
}
