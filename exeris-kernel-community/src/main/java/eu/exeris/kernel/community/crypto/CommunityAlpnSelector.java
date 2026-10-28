/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;


/**
 * Server-side ALPN protocol selector for the Community TLS stack.
 *
 * <p>Registers an OpenSSL {@code SSL_CTX_set_alpn_select_cb} upcall stub
 * at class load time. Selects only protocols the server's configured ALPN list names, preferring h2
 * over http/1.1 when both are named. Which of the two a server may select is the callback argument,
 * derived from that list by {@link #argumentFor(List)}.
 *
 * @since 0.5
 */
final class CommunityAlpnSelector {

	/* default */ static final int SSL_TLSEXT_ERR_OK = 0;
	/* default */ static final int SSL_TLSEXT_ERR_NOACK = 3;

	/** Callback argument: {@code h2} and {@code http/1.1} are selectable, {@code h2} preferred. */
	/* default */ static final long ARG_HTTP_2_SELECTABLE = 0L;

	/** Callback argument: only {@code http/1.1} is selectable. */
	/* default */ static final long ARG_HTTP_1_1_ONLY = 1L;

	/** Callback argument: only {@code h2} is selectable. */
	/* default */ static final long ARG_HTTP_2_ONLY = 2L;

	/** {@code STUB} is the permanent Panama upcall trampoline, or {@code null} if unavailable. */
	/* default */ static final MemorySegment STUB = resolveStub();

	private static final long NULL_PTR = 0L;
	private static final byte[] PROTO_H2 = {'h', '2'};
	private static final byte[] PROTO_HTTP_1_1 = {'h', 't', 't', 'p', '/', '1', '.', '1'};

	private CommunityAlpnSelector() { }

	/**
	 * The callback argument for a server whose configured ALPN list is {@code protocols}, or empty
	 * when the list names neither protocol, in which case no callback is registered.
	 *
	 * @param protocols the server's configured ALPN protocol names
	 * @return {@link #ARG_HTTP_2_SELECTABLE} when the list names both, {@link #ARG_HTTP_2_ONLY} when it
	 *         names only h2, {@link #ARG_HTTP_1_1_ONLY} when it names only http/1.1, else empty
	 */
	/* default */ static OptionalLong argumentFor(List<String> protocols) {
		boolean namesH2 = protocols.contains("h2");
		boolean namesHttp11 = protocols.contains("http/1.1");
		if (namesH2 && namesHttp11) {
			return OptionalLong.of(ARG_HTTP_2_SELECTABLE);
		}
		if (namesH2) {
			return OptionalLong.of(ARG_HTTP_2_ONLY);
		}
		if (namesHttp11) {
			return OptionalLong.of(ARG_HTTP_1_1_ONLY);
		}
		return OptionalLong.empty();
	}

	/**
	 * The protocol this server selects from a client's offer, a wire list of length-prefixed names.
	 *
	 * <p>{@code h2} wins when it is allowed and offered; otherwise {@code http/1.1} is selected if it
	 * is allowed and offered. An entry whose length runs past the end of the list ends the scan, so
	 * only the entries before it are considered.
	 *
	 * @param offer          the client's ALPN offer
	 * @param h2Allowed      whether this server may select {@code h2}
	 * @param http11Allowed  whether this server may select {@code http/1.1}
	 * @return the selected name's position in {@code offer}, or empty when nothing offered is selectable
	 */
	/* default */ static Optional<Selection> select(
			MemorySegment offer, boolean h2Allowed, boolean http11Allowed) {
		if (h2Allowed) {
			Optional<Selection> h2Selection = find(offer, PROTO_H2);
			if (h2Selection.isPresent()) {
				return h2Selection;
			}
		}
		return http11Allowed ? find(offer, PROTO_HTTP_1_1) : Optional.empty();
	}

	/**
	 * A selected protocol name inside the client's offer.
	 *
	 * @param offset the position of the name's first byte within the offer
	 * @param length the name's length in bytes
	 */
	/* default */ record Selection(long offset, int length) {
	}

	// Panama/FFM stub registration: RuntimeException absorbed to ensure graceful degradation —
	// if the native upcall cannot be installed, STUB returns null and ALPN selection is skipped
	// without propagating a boot-time failure.
	@SuppressWarnings("PMD.AvoidCatchingGenericException")
	private static MemorySegment resolveStub() {
		try {
			CommunityForeignCall call = CommunityForeignCall.ALPN_SELECT_CALLBACK;
			java.lang.invoke.MethodHandle callbackHandle = MethodHandles.lookup()
					.findStatic(call.targetClass(), call.targetMethod(),
							call.descriptor().toMethodType());
			return Linker.nativeLinker().upcallStub(callbackHandle, call.descriptor(), Arena.global());
		} catch (NoSuchMethodException | IllegalAccessException | RuntimeException _) {
			return null;
		}
	}

	// Panama FFI upcall for SSL_CTX_set_alpn_select_cb — ABI is fixed by OpenSSL:
	//   int cb(SSL *ssl, const u8 **out, u8 *outlen, const u8 *in, unsigned int inlen, void *arg)
	// 'ssl' and 'arg' are positional ABI parameters that must remain in the MethodType and
	// FunctionDescriptor even though they are not referenced in the Java body.
	// Removing them would cause an argument-register mismatch and a SIGSEGV at runtime.
	// Do NOT apply CodeQL / static-analysis suggestions to remove these parameters.
	@SuppressWarnings({"PMD.AvoidCatchingGenericException", "java:S1144", "java:S1172", "unused"})
	private static int selectCallback(
			long ssl, long outPtr, long outlenPtr, long alpnInput, int inlen, long arg) {
		try {
			if (alpnInput == NULL_PTR || inlen <= 0) {
				return SSL_TLSEXT_ERR_NOACK;
			}
			MemorySegment offer = MemorySegment.ofAddress(alpnInput).reinterpret(inlen);
			Optional<Selection> chosen = select(offer,
					arg == ARG_HTTP_2_SELECTABLE || arg == ARG_HTTP_2_ONLY,
					arg == ARG_HTTP_2_SELECTABLE || arg == ARG_HTTP_1_1_ONLY);
			if (chosen.isEmpty()) {
				return SSL_TLSEXT_ERR_NOACK;
			}
			long selectedAddr = alpnInput + chosen.get().offset();
			MemorySegment.ofAddress(outPtr)
					.reinterpret(ValueLayout.ADDRESS.byteSize())
					.set(ValueLayout.ADDRESS, 0, MemorySegment.ofAddress(selectedAddr));
			MemorySegment.ofAddress(outlenPtr).reinterpret(1)
					.set(ValueLayout.JAVA_BYTE, 0, (byte) chosen.get().length());
			return SSL_TLSEXT_ERR_OK;
		} catch (RuntimeException _) {
			return SSL_TLSEXT_ERR_NOACK;
		}
	}

	private static Optional<Selection> find(MemorySegment offer, byte[] protocol) {
		long nameOffset = indexOf(offer, protocol);
		return nameOffset < 0 ? Optional.empty() : Optional.of(new Selection(nameOffset, protocol.length));
	}

	/** The position of {@code protocol}'s first byte in {@code offer}, or {@code -1} when it is not named. */
	private static long indexOf(MemorySegment offer, byte[] protocol) {
		long offset = 0;
		while (offset < offer.byteSize()) {
			int length = Byte.toUnsignedInt(offer.get(ValueLayout.JAVA_BYTE, offset));
			if (offset + 1 + length > offer.byteSize()) {
				break;
			}
			if (length == protocol.length && sameBytes(offer.asSlice(offset + 1, length), protocol)) {
				return offset + 1;
			}
			offset += 1 + length;
		}
		return -1L;
	}

	private static boolean sameBytes(MemorySegment name, byte[] protocol) {
		return name.mismatch(MemorySegment.ofArray(protocol)) == -1L;
	}
}
