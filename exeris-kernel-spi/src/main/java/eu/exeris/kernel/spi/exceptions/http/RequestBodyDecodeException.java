/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.http;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown by {@link eu.exeris.kernel.spi.http.HttpRequestBodyDecoder#decode} when the offered bytes
 * are not valid for the requested target type — malformed syntax, or a payload that does not bind.
 *
 * <h2>Why this exists as its own type</h2>
 * <p>ADR-036 §2 places HTTP status mapping on the handler, not the codec SPI. A handler can only
 * honour that if it can tell two failures apart:
 * <ul>
 *   <li><b>the caller sent bad bytes</b> — {@code 400 Bad Request}; this type;</li>
 *   <li><b>the server has no decoder</b> for the target type / content-type — a deployment fault,
 *       {@code 5xx}; still an {@link IllegalStateException}.</li>
 * </ul>
 *
 * <p>Before this type both arrived as {@code IllegalStateException}, because the decoder contract
 * asked drivers to wrap binding exceptions in a {@code java.*} {@code RuntimeException} and
 * {@code IllegalStateException} was the natural choice. The mapping a handler was told to perform
 * was therefore not expressible, and every malformed request reached the caller as a 500 — the
 * server taking the blame for a client's typo. Found by an Entity-First consumer whose generated
 * handlers implement exactly the ADR-036 §2 split.
 *
 * <h2>The Wall</h2>
 * <p>This is an SPI-owned type, so wrapping in it satisfies the driver-opacity rule as fully as
 * {@code IllegalStateException} did: no binding-specific exception (Jackson, Protobuf, …) crosses
 * the SPI boundary. Opacity is about keeping <em>driver</em> types out, not about restricting the
 * kernel to {@code java.*}.
 *
 * <h2>Zero-Allocation Contract</h2>
 * <p>Follows the {@link ExerisKernelException} Glass-Box pattern: the target type name and the body
 * length are stored in {@link #rawArgs()} as typed primitives, never formatted into the message.
 * The body itself is request content and is never captured — a malformed payload is exactly the
 * kind of data most likely to be a secret sent to the wrong endpoint.
 *
 * <p><b>The guarantee covers the cause chain, and that is where it is easy to lose.</b> This type
 * retains the driver's own failure as {@link #getCause()} for forensics, and every stack-trace
 * printer and logging bridge walks that chain — so a binding whose exception message quotes the
 * offending input would publish the body through the back door while this class's own message stayed
 * clean. Jackson 3 does not: {@code StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION} is off by default
 * (it was on in Jackson 2), so a parse failure reads {@code [Source: REDACTED …]}. That is a library
 * default an application's {@code JsonMapperCustomizer} can flip, so it is not left to trust —
 * {@code AbstractHttpRequestBodyDecoderTck} asserts that no link in the chain carries body content,
 * and any driver enabling source capture fails the contract rather than the deployment discovering
 * it in a log.
 *
 * <h2>rawArgs Binary Layout</h2>
 * <pre>
 * EX-HTTP-4013 (malformed / unbindable request body):
 *   index 0 → String targetTypeName
 *   index 1 → long   bodySize
 * </pre>
 *
 * @since 0.12.0
 */
public final class RequestBodyDecodeException extends ExerisKernelException {

    private static final String MSG_MALFORMED = "HTTP request body could not be decoded into the target type";

    /**
     * General-purpose constructor — used when no specific factory method matches the scenario.
     *
     * @param errorCode {@code EX-HTTP-*} code; must not be {@code null}
     * @param message   static message template; no runtime formatting
     * @param cause     the binding-specific failure, or {@code null}
     * @param rawArgs   raw request-scoped arguments for Glass-Box telemetry; never body content
     */
    public RequestBodyDecodeException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    /**
     * Creates a {@code RequestBodyDecodeException} for a body the decoder could not bind, carrying
     * the driver's own exception as the cause.
     *
     * @param targetTypeName binary name of the requested payload type
     * @param bodySize       bytes offered to the decoder
     * @param cause          the binding-specific failure; retained as the cause, never rethrown
     * @return a new exception carrying {@value KernelErrorCodes#EX_HTTP_4013}
     */
    public static RequestBodyDecodeException malformedBody(String targetTypeName, long bodySize, Throwable cause) {
        return new RequestBodyDecodeException(
                KernelErrorCodes.EX_HTTP_4013, MSG_MALFORMED, cause, targetTypeName, bodySize);
    }
}
