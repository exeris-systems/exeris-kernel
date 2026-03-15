/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.core.crypto.tls.OffHeapTlsEngine;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.exceptions.crypto.TlsDecryptException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Community wrapper over {@link OffHeapTlsEngine} with FD-owner bind support.
 *
 * @since 0.5.0
 */
@SuppressWarnings({
	"PMD.CyclomaticComplexity",
	"PMD.TooManyMethods",
	"PMD.CommentDefaultAccessModifier",
	"PMD.ExceptionAsFlowControl",
	"PMD.UseTryWithResources"
})
public final class CommunityTlsEngine implements TlsEngine {

	private static final int SSL_SUCCESS = 1;

	private final OffHeapTlsEngine delegate;
	private final MethodHandle sslSetFd;
	private final CoreSslHandles.CtxHandles ctxHandles;
	private final long sslCtxPtr;
	private final MemoryAllocator ownedAllocator;
	private final boolean jfrEnabled;

	private final AtomicBoolean bound = new AtomicBoolean(false);
	private final AtomicBoolean closed = new AtomicBoolean(false);

	CommunityTlsEngine(OffHeapTlsEngine delegate,
					   MethodHandle sslSetFd,
					   CoreSslHandles.CtxHandles ctxHandles,
					   long sslCtxPtr,
					   MemoryAllocator ownedAllocator,
					   boolean jfrEnabled) {
		this.delegate = delegate;
		this.sslSetFd = sslSetFd;
		this.ctxHandles = ctxHandles;
		this.sslCtxPtr = sslCtxPtr;
		this.ownedAllocator = ownedAllocator;
		this.jfrEnabled = jfrEnabled;
	}

	/**
	 * Binds OpenSSL TLS session to socket file descriptor and transitions to handshake phase.
	 */
	public void bindSocketChannel(SocketChannel channel) {
		if (channel == null) {
			throw new TlsHandshakeException("SocketChannel must not be null");
		}
		bindFileDescriptor(extractFd(channel));
	}

	public void bindFileDescriptor(int fileDescriptor) {
		if (bound.get()) {
			return;
		}
		if (fileDescriptor < 0) {
			throw new TlsHandshakeException("SSL_set_fd failed: invalid file descriptor");
		}
		if (!bound.compareAndSet(false, true)) {
			return;
		}
		try {
			int bindResult = delegate.bindTransportFd(sslSetFd, fileDescriptor);
			if (bindResult != SSL_SUCCESS) {
				throw new TlsHandshakeException(bindResult, "SSL_set_fd failed");
			}
			delegate.notifyBound();
		} catch (TlsHandshakeException handshakeException) {
			bound.set(false);
			throw handshakeException;
		}
	}

	@Override
	public TlsStatus beginHandshake(LoanedBuffer outbound) {
		try {
			ensureBound();
			TlsStatus status = delegate.beginHandshake(outbound);
			if (jfrEnabled) {
				CommunityTlsHandshakeEvent.emit(status == TlsStatus.FINISHED, 0);
			}
			return status;
		} catch (TlsHandshakeException exception) {
			if (jfrEnabled) {
				CommunityTlsHandshakeEvent.emit(false, -1);
			}
			throw exception;
		}
	}

	@Override
	public TlsStatus unwrap(LoanedBuffer ciphertext, LoanedBuffer plaintext) {
		ensureBoundForDecrypt();
		return delegate.unwrap(ciphertext, plaintext);
	}

	@Override
	public TlsStatus wrap(LoanedBuffer plaintext, LoanedBuffer ciphertext) {
		ensureBound();
		return delegate.wrap(plaintext, ciphertext);
	}

	@Override
	public boolean isHandshakeComplete() {
		return delegate.isHandshakeComplete();
	}

	@Override
	public String negotiatedProtocol() {
		return delegate.negotiatedProtocol();
	}

	@Override
	public void initiateShutdown(LoanedBuffer outbound) {
		if (bound.get()) {
			delegate.initiateShutdown(outbound);
		}
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		try {
			delegate.close();
		} finally {
			try {
				ctxHandles.invokeCtxFree(sslCtxPtr);
			} finally {
				if (ownedAllocator != null) {
					ownedAllocator.close();
				}
			}
		}
	}

	private static int extractFd(SocketChannel channel) {
		try {
			MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
					channel.getClass(),
					MethodHandles.lookup());
			MethodHandle getFdVal = lookup.findVirtual(
					channel.getClass(),
					"getFDVal",
					MethodType.methodType(int.class));
			return (int) getFdVal.invoke(channel);
		} catch (Throwable throwable) { //NOPMD AvoidCatchingGenericException — MethodHandle invocation contract
			throw new TlsHandshakeException(
					"SocketChannel FD access denied; use bindFileDescriptor(int)",
					throwable);
		}
	}

	private void ensureBound() {
		if (!bound.get()) {
			throw new TlsHandshakeException("TLS engine is not bound to socket FD");
		}
	}

	private void ensureBoundForDecrypt() {
		if (!bound.get()) {
			throw new TlsDecryptException("TLS engine is not bound to socket FD");
		}
	}
}