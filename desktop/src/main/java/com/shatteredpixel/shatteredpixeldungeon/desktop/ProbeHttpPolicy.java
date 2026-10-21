/* SPDX-License-Identifier: GPL-3.0-or-later
 * Local HTTP boundary added by SPD AI Probe contributors, 2026.
 */
package com.shatteredpixel.shatteredpixeldungeon.desktop;

/** Reject DNS rebinding hosts before routing, including static and capability routes. */
final class ProbeHttpPolicy {
    static void validate(String host, String origin) {
        if (!"127.0.0.1:18765".equals(host))
            throw new IllegalArgumentException("foreign_host");
        if (origin != null && !"http://127.0.0.1:18765".equals(origin))
            throw new IllegalArgumentException("foreign_origin");
    }
}
