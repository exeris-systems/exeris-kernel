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

import java.lang.reflect.Method;
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
	"PMD.AvoidCatchingGenericException",
	"PMD.UseTryWithResources"
})
public final class CommunityTlsEngine implements TlsEngine {

	private static final int SSL_SUCCESS = 1;

	private final OffHeapTlsEngine delegate;
	private final java.lang.invoke.MethodHandle sslSetFd;
	private final CoreSslHandles.CtxHandles ctxHandles;
	private final long sslCtxPtr;
	private final MemoryAllocator ownedAllocator;

	private final AtomicBoolean bound = new AtomicBoolean(false);
	private final AtomicBoolean closed = new AtomicBoolean(false);

	CommunityTlsEngine(OffHeapTlsEngine delegate,
					   java.lang.invoke.MethodHandle sslSetFd,
					   CoreSslHandles.CtxHandles ctxHandles,
					   long sslCtxPtr,
					   MemoryAllocator ownedAllocator) {
		this.delegate = delegate;
		this.sslSetFd = sslSetFd;
		this.ctxHandles = ctxHandles;
		this.sslCtxPtr = sslCtxPtr;
		this.ownedAllocator = ownedAllocator;
	}

	/**
	 * Binds OpenSSL TLS session to socket file descriptor and transitions to handshake phase.
	 */
	public void bindSocketChannel(SocketChannel channel) {
		int fileDescriptor = extractFd(channel);
		bindFileDescriptor(fileDescriptor);
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
		long sslPtr = delegate.sslPointerForDiagnostics();
		try {
			int bindResult = (int) sslSetFd.invokeExact(sslPtr, fileDescriptor);
			if (bindResult != SSL_SUCCESS) {
				bound.set(false);
				throw new TlsHandshakeException("SSL_set_fd failed (result=" + bindResult + ")");
			}
			delegate.notifyBound();
		} catch (TlsHandshakeException handshakeException) {
			bound.set(false);
			throw handshakeException;
		} catch (Throwable throwable) {
			bound.set(false);
			throw new TlsHandshakeException("SSL_set_fd invocation failed", throwable);
		}
	}

	@Override
	public TlsStatus beginHandshake(LoanedBuffer outbound) {
		if (!bound.get()) {
			outbound.setSize(0);
			CommunityTlsHandshakeEvent.emit(false, 0);
			return TlsStatus.NEED_HANDSHAKE;
		}
		try {
			TlsStatus status = delegate.beginHandshake(outbound);
			CommunityTlsHandshakeEvent.emit(status == TlsStatus.FINISHED, 0);
			return status;
		} catch (TlsHandshakeException exception) {
			CommunityTlsHandshakeEvent.emit(false, -1);
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
			Method getFdVal = channel.getClass().getDeclaredMethod("getFDVal");
			return (int) getFdVal.invoke(channel);
		} catch (ReflectiveOperationException reflectionError) {
			throw new TlsHandshakeException("SocketChannel FD extraction failed", reflectionError);
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