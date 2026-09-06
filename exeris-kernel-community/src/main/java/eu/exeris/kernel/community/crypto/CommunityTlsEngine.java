/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Community wrapper over {@link OffHeapTlsEngine} that adds explicit socket file descriptor
 * binding, ordered delegate/context/allocator close, and an optional JFR handshake event.
 *
 * <p>{@link #bindFileDescriptor(int)} and {@link #bindSocketChannel(SocketChannel)} bind the
 * underlying OpenSSL session exactly once. {@link #wrap}, {@link #unwrap} and
 * {@link #beginHandshake} throw {@link TlsHandshakeException} or {@link TlsDecryptException}
 * until a bind succeeds, but {@link #initiateShutdown} silently no-ops while unbound and
 * {@link #isHandshakeComplete()} / {@link #negotiatedProtocol()} delegate unconditionally,
 * with no bound check of their own.
 *
 * <p><b>Thread confinement:</b> per {@link TlsEngine}'s contract, an instance is owned by a
 * single carrier or virtual thread for {@link #wrap}, {@link #unwrap} and
 * {@link #beginHandshake}; {@link #bindFileDescriptor(int)} and {@link #close()} use
 * compare-and-set guards so a bind or close reached from another thread does not corrupt the
 * bound or closed state.
 * <p><b>Ownership:</b> {@link #close()} releases the native SSL context via
 * {@code SSL_CTX_free} and, when this engine was constructed with an allocator created solely
 * for it, closes that allocator too; an allocator shared with the host runtime is left for its
 * owner to close.
 *
 * @since 0.5
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
	private final AtomicBoolean delegateBoundNotified = new AtomicBoolean(false);
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
	 * Resolves {@code channel}'s raw file descriptor via reflection and binds this engine to it,
	 * exactly as {@link #bindFileDescriptor(int)}.
	 *
	 * @param channel the connected socket channel whose file descriptor is bound
	 * @throws TlsHandshakeException ({@code EX-NET-2001}) if the engine is already closed, if
	 *         {@code channel} is {@code null} or its file descriptor cannot be resolved, or if
	 *         the native {@code SSL_set_fd} call fails
	 */
	public void bindSocketChannel(SocketChannel channel) {
		bindFileDescriptor(SocketChannelFdAccess.requireFd(channel));
	}

	/**
	 * Binds this engine's underlying OpenSSL session to a raw socket file descriptor via
	 * {@code SSL_set_fd}, then transitions to the handshake phase.
	 *
	 * <p>A call after a successful bind returns without rebinding. A failed bind clears the
	 * bound state, so a later call may retry.
	 *
	 * @param fileDescriptor the socket's raw file descriptor; must be {@code >= 0}
	 * @throws TlsHandshakeException ({@code EX-NET-2001}) if the engine is already closed, if
	 *         {@code fileDescriptor} is negative, or if the native {@code SSL_set_fd} call fails
	 */
	public void bindFileDescriptor(int fileDescriptor) {
		if (closed.get()) {
			throw new TlsHandshakeException("TLS engine is already closed");
		}
		if (bound.get()) {
			return;
		}
		if (fileDescriptor < 0) {
			throw new TlsHandshakeException("SSL_set_fd failed: invalid file descriptor");
		}
		if (!bound.compareAndSet(false, true)) {
			if (closed.get()) {
				throw new TlsHandshakeException("TLS engine is already closed");
			}
			return;
		}
		try {
			int bindResult = delegate.bindTransportFd(sslSetFd, fileDescriptor);
			if (bindResult != SSL_SUCCESS) {
				throw new TlsHandshakeException(bindResult, "SSL_set_fd failed");
			}
			notifyDelegateBoundOnce();
		} catch (TlsHandshakeException handshakeException) {
			bound.set(false);
			throw handshakeException;
		}
	}

	/**
	 * Confirms that binding already completed via {@link #bindFileDescriptor(int)} or
	 * {@link #bindSocketChannel(SocketChannel)} and forwards the confirmation to the delegate
	 * engine exactly once.
	 *
	 * @throws TlsHandshakeException ({@code EX-NET-2001}) if called before a successful bind
	 */
	@Override
	public void notifyBound() {
		if (!bound.get()) {
			throw new TlsHandshakeException(
					"Explicit FD binding is required before notifyBound()");
		}
		notifyDelegateBoundOnce();
	}

	/**
	 * Requires this engine to be bound, then delegates to the wrapped {@link OffHeapTlsEngine}
	 * and, when this engine was constructed with JFR enabled, commits a
	 * {@link CommunityTlsHandshakeEvent} recording completion or failure.
	 *
	 * @param outbound buffer for outbound handshake bytes
	 * @return the handshake status returned by the delegate
	 * @throws TlsHandshakeException ({@code EX-NET-2001}) if the engine is not bound, or if the
	 *         delegate's handshake attempt fails
	 */
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

	/**
	 * Requires this engine to be bound, then delegates to the wrapped {@link OffHeapTlsEngine}.
	 *
	 * @param ciphertext inbound encrypted bytes
	 * @param plaintext output buffer for decrypted application data
	 * @return the status returned by the delegate
	 * @throws TlsDecryptException ({@code EX-NET-2003}) if the engine is not bound
	 */
	@Override
	public TlsStatus unwrap(LoanedBuffer ciphertext, LoanedBuffer plaintext) {
		ensureBoundForDecrypt();
		return delegate.unwrap(ciphertext, plaintext);
	}

	/**
	 * Requires this engine to be bound, then delegates to the wrapped {@link OffHeapTlsEngine}.
	 *
	 * @param plaintext application data to encrypt
	 * @param ciphertext output buffer for encrypted bytes
	 * @return the status returned by the delegate
	 * @throws TlsHandshakeException ({@code EX-NET-2001}) if the engine is not bound
	 */
	@Override
	public TlsStatus wrap(LoanedBuffer plaintext, LoanedBuffer ciphertext) {
		ensureBound();
		return delegate.wrap(plaintext, ciphertext);
	}

	/**
	 * Returns {@code true} if the delegate engine reports the handshake complete.
	 */
	@Override
	public boolean isHandshakeComplete() {
		return delegate.isHandshakeComplete();
	}

	/**
	 * Returns the negotiated ALPN protocol reported by the delegate engine, or {@code null} if
	 * none was negotiated.
	 */
	@Override
	public String negotiatedProtocol() {
		return delegate.negotiatedProtocol();
	}

	/**
	 * Delegates to the wrapped {@link OffHeapTlsEngine} when this engine is bound; a no-op
	 * otherwise.
	 *
	 * @param outbound buffer for the {@code close_notify} alert bytes
	 */
	@Override
	public void initiateShutdown(LoanedBuffer outbound) {
		if (bound.get()) {
			delegate.initiateShutdown(outbound);
		}
	}

	/**
	 * Releases this engine's native SSL context and, when this engine was constructed with an
	 * allocator of its own, closes that allocator too. Idempotent — a call after the first has
	 * no effect. The delegate is closed first; the context is freed and the allocator closed
	 * even if the delegate throws.
	 */
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

	private void notifyDelegateBoundOnce() {
		if (!delegateBoundNotified.compareAndSet(false, true)) {
			return;
		}
		try {
			delegate.notifyBound();
		} catch (RuntimeException exception) { // NOPMD: Delegate may throw various unchecked exceptions
			delegateBoundNotified.set(false);
			throw exception;
		}
	}
}