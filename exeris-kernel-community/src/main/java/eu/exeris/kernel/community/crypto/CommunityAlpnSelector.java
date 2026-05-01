/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.crypto;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Server-side ALPN protocol selector for the Community TLS stack.
 *
 * <p>Registers an OpenSSL {@code SSL_CTX_set_alpn_select_cb} upcall stub
 * at class load time. Prefers h2 over http/1.1 per RFC 7301 §3.1.
 *
 * @since 0.5.0
 */
final class CommunityAlpnSelector {

	/* default */ static final int SSL_TLSEXT_ERR_OK = 0;
	/* default */ static final int SSL_TLSEXT_ERR_NOACK = 3;

	/** {@code STUB} is the permanent Panama upcall trampoline, or {@code null} if unavailable. */
	/* default */ static final MemorySegment STUB = resolveStub();

	private static final long NULL_PTR = 0L;
	private static final byte[] PROTO_HTTP_1_1 = {'h', 't', 't', 'p', '/', '1', '.', '1'};

	private CommunityAlpnSelector() { }

	// Panama/FFM stub registration: RuntimeException absorbed to ensure graceful degradation —
	// if the native upcall cannot be installed, STUB returns null and ALPN selection is skipped
	// without propagating a boot-time failure.
	@SuppressWarnings("PMD.AvoidCatchingGenericException")
	private static MemorySegment resolveStub() {
		try {
			java.lang.invoke.MethodHandle callbackHandle = MethodHandles.lookup()
					.findStatic(CommunityAlpnSelector.class, "selectCallback",
							MethodType.methodType(int.class, long.class, long.class,
									long.class, long.class, int.class, long.class));
			FunctionDescriptor alpnCbDesc = FunctionDescriptor.of(
					ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
					ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG);
			return Linker.nativeLinker().upcallStub(callbackHandle, alpnCbDesc, Arena.global());
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
			MemorySegment inBuf = MemorySegment.ofAddress(alpnInput).reinterpret(inlen);
			long h2Addr = findH2Addr(inBuf, alpnInput, inlen);
			long h11Addr = h2Addr == NULL_PTR ? findH11Addr(inBuf, alpnInput, inlen) : NULL_PTR;
			long selectedAddr;
			byte selectedLen;
			if (h2Addr == NULL_PTR) {
				if (h11Addr == NULL_PTR) {
					return SSL_TLSEXT_ERR_NOACK;
				}
				selectedAddr = h11Addr;
				selectedLen = 8;
			} else {
				selectedAddr = h2Addr;
				selectedLen = 2;
			}
			MemorySegment.ofAddress(outPtr)
					.reinterpret(ValueLayout.ADDRESS.byteSize())
					.set(ValueLayout.ADDRESS, 0, MemorySegment.ofAddress(selectedAddr));
			MemorySegment.ofAddress(outlenPtr).reinterpret(1)
					.set(ValueLayout.JAVA_BYTE, 0, selectedLen);
			return SSL_TLSEXT_ERR_OK;
		} catch (RuntimeException _) {
			return SSL_TLSEXT_ERR_NOACK;
		}
	}

	private static long findH2Addr(MemorySegment inBuf, long inBase, int inlen) {
		long offset = 0;
		while (offset < inlen) {
			int len = Byte.toUnsignedInt(inBuf.get(ValueLayout.JAVA_BYTE, offset));
			if (offset + 1 + len > inlen) {
				break;
			}
			if (len == 2
					&& inBuf.get(ValueLayout.JAVA_BYTE, offset + 1) == (byte) 'h'
					&& inBuf.get(ValueLayout.JAVA_BYTE, offset + 2) == (byte) '2') {
				return inBase + offset + 1;
			}
			offset += 1 + len;
		}
		return NULL_PTR;
	}

	private static long findH11Addr(MemorySegment inBuf, long inBase, int inlen) {
		long offset = 0;
		while (offset < inlen) {
			int len = Byte.toUnsignedInt(inBuf.get(ValueLayout.JAVA_BYTE, offset));
			if (offset + 1 + len > inlen) {
				break;
			}
			if (len == 8 && isHttp11(inBuf, offset + 1)) {
				return inBase + offset + 1;
			}
			offset += 1 + len;
		}
		return NULL_PTR;
	}

	private static boolean isHttp11(MemorySegment inBuf, long offset) {
		for (int i = 0; i < 8; i++) {
			if (inBuf.get(ValueLayout.JAVA_BYTE, offset + i) != PROTO_HTTP_1_1[i]) {
				return false;
			}
		}
		return true;
	}
}
