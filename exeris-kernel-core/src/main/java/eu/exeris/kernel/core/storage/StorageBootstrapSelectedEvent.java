/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.storage;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event recording which {@code BlobStorageProvider} bootstrap selected (ADR-056).
 *
 * <p>Mirrors {@code SchedulingBootstrapSelectedEvent}, with the priority kept even though it decides
 * nothing here: a future driver that raises it would change nothing about selection, and a recording
 * that shows equal priorities is what tells a reader the id — not the ranking — is what chose.
 * Single-phase commit on the bootstrap thread.
 *
 * <p><b>The location is recorded without userinfo.</b> The event is committed before the selected
 * driver reads its configuration, so a driver that refuses a location carrying credentials cannot keep
 * them out of the recording. The event is also driver-agnostic: a filesystem location is a directory,
 * in which {@code @} is an ordinary character. So only the userinfo of an authority —
 * {@code scheme://userinfo@host} — is replaced, and every other location is recorded as configured.
 *
 * @since 0.12
 */
@Name("eu.exeris.kernel.storage.StorageBootstrapSelected")
@Label("Storage Bootstrap Selected")
@Description("Records the BlobStorageProvider chosen by configured id at bootstrap")
@Category({"Exeris Kernel", "Storage"})
@StackTrace(false)
public final class StorageBootstrapSelectedEvent extends Event {

    /** What stands in for the userinfo of a recorded location. */
    private static final String USERINFO_MARKER = "<userinfo>";

    private static final String AUTHORITY_PREFIX = "://";

    /** The characters that end an authority: the start of a path, a query or a fragment. */
    private static final String AUTHORITY_TERMINATORS = "/?#";

    /** The last of these in an authority ends its userinfo. */
    private static final char USERINFO_END = '@';

    /** The characters other than letters and digits a scheme may contain. */
    private static final String SCHEME_PUNCTUATION = "+-.";

    @Label("Provider Class")
    /* default */ String providerClass;

    @Label("Provider Id")
    /* default */ String providerId;

    @Label("Priority")
    /* default */ int priority;

    @Label("Location")
    /* default */ String location;

    /**
     * Creates an unrecorded event.
     *
     * <p>{@link #emit} assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public StorageBootstrapSelectedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Records the selection.
     *
     * @param providerClass implementation class name of the selected provider
     * @param providerId    its stable provider id — the value that chose it
     * @param priority      its priority, recorded rather than used
     * @param location      the configured root location; recorded with the userinfo of its
     *                      authority, if any, replaced by {@code <userinfo>}
     */
    public static void emit(String providerClass, String providerId, int priority, String location) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        StorageBootstrapSelectedEvent event = new StorageBootstrapSelectedEvent();
        if (event.isEnabled()) {
            event.providerClass = providerClass;
            event.providerId = providerId;
            event.priority = priority;
            event.location = recordedLocation(location);
            event.commit();
        }
    }

    /**
     * The location with the userinfo of its authority replaced by {@link #USERINFO_MARKER}.
     *
     * <p>Only a location that begins with a scheme followed by {@code ://} has an authority; it runs
     * to the first {@code /}, {@code ?} or {@code #}, and its userinfo ends at its last {@code @}. Any
     * other {@code @} is part of a path, a query or a fragment and is kept. Textual rather than through
     * {@link java.net.URI}, because a location {@code URI} cannot parse may still carry credentials.
     *
     * @param location the configured location
     * @return the location as the event records it
     */
    /* default */ static String recordedLocation(String location) {
        int authorityStart = authorityStart(location);
        if (authorityStart < 0) {
            return location;
        }
        int userInfoEnd = -1;
        for (int index = authorityStart; index < location.length(); index++) {
            char current = location.charAt(index);
            if (AUTHORITY_TERMINATORS.indexOf(current) >= 0) {
                break;
            }
            if (current == USERINFO_END) {
                userInfoEnd = index;
            }
        }
        if (userInfoEnd < 0) {
            return location;
        }
        return location.substring(0, authorityStart) + USERINFO_MARKER + location.substring(userInfoEnd);
    }

    /**
     * Index just past the {@code ://} that follows a leading scheme, or {@code -1} if the location
     * does not begin with one. A scheme is a letter followed by letters, digits, {@code +}, {@code -}
     * or {@code .} (RFC 3986 §3.1).
     */
    private static int authorityStart(String location) {
        int separator = location.indexOf(AUTHORITY_PREFIX);
        if (separator <= 0 || !isAsciiLetter(location.charAt(0))) {
            return -1;
        }
        for (int index = 1; index < separator; index++) {
            if (!isSchemeCharacter(location.charAt(index))) {
                return -1;
            }
        }
        return separator + AUTHORITY_PREFIX.length();
    }

    private static boolean isSchemeCharacter(char candidate) {
        return isAsciiLetter(candidate)
                || Character.isDigit(candidate) && candidate < 0x80
                || SCHEME_PUNCTUATION.indexOf(candidate) >= 0;
    }

    private static boolean isAsciiLetter(char candidate) {
        return candidate < 0x80 && Character.isLetter(candidate);
    }
}
