// SPDX-License-Identifier: MIT
package dev.blocknet.client;

import dev.blocknet.debug.DebugPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Latest debug frame per watched bot, on the client thread. Frames older than three seconds are dropped. */
public final class DebugClient {
    record View(DebugPayload frame, long received) {}

    private static final long STALE_MILLIS = 3000;
    private static final Map<String, View> VIEWS = new LinkedHashMap<>();

    private DebugClient() {}

    /** Called from the payload handler on the client thread; an empty frame clears the overlay. */
    public static void receive(DebugPayload payload) {
        if (payload.entityId() < 0) {
            VIEWS.clear();
            return;
        }
        VIEWS.remove(payload.name());
        VIEWS.put(payload.name(), new View(payload, System.currentTimeMillis()));
    }

    static List<View> views() {
        long now = System.currentTimeMillis();
        VIEWS.values().removeIf(view -> now - view.received() > STALE_MILLIS);
        return new ArrayList<>(VIEWS.values());
    }

    static void clear() {
        VIEWS.clear();
    }
}
