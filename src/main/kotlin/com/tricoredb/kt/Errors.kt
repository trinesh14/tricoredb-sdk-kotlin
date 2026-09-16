package com.tricoredb.kt

/** The stable, machine-readable error codes a TriCoreDB server may send. Branch on these, never on messages. */
public object ErrorCodes {
    /** The request reached a follower; see [TriCoreException.leaderHint]. */
    public const val NOT_LEADER: String = "not_leader"
    /** The principal lacks the permission the operation needs. */
    public const val PERM_DENIED: String = "perm.denied"
    /** The request was understood but is not valid. */
    public const val REQUEST_INVALID: String = "request.invalid"
    /** The request could not be parsed. */
    public const val REQUEST_MALFORMED: String = "request.malformed"
    /** The module serving the operation is disabled on this server. */
    public const val ENGINE_DISABLED: String = "engine.disabled"
    /** A server limit was exceeded. */
    public const val LIMIT_EXCEEDED: String = "limit.exceeded"
    /** The operation conflicts with current state. */
    public const val STATE_CONFLICT: String = "state.conflict"
    /** An internal server failure. */
    public const val INTERNAL: String = "internal"
    /** Frame-level: a legal frame arrived where it is not legal. */
    public const val PROTOCOL: String = "protocol"
    /** Frame-level: a frame arrived before the handshake or authentication it needs. */
    public const val ORDER: String = "order"
    /** Frame-level: a REQUEST payload could not be deserialised. */
    public const val REQUEST: String = "request"
    /** Frame-level: an unreadable frame header version. */
    public const val FRAME_VERSION: String = "frame_version"
    /** Frame-level: an unknown frame tag. */
    public const val FRAME_TAG: String = "frame_tag"
    /** Frame-level: the HELLO could not be parsed. */
    public const val HANDSHAKE_MALFORMED: String = "handshake_malformed"
    /** Frame-level: a payload above the ceiling for its tag. */
    public const val FRAME_TOO_LARGE: String = "frame_too_large"
    /** Frame-level: the server is at its connection ceiling; retryable. */
    public const val CONNECTION_LIMIT: String = "connection_limit"
    /** Frame-level: the session's credentials were revoked. */
    public const val AUTH_REVOKED: String = "auth_revoked"
    /** Frame-level: a session transaction sat idle too long and was rolled back. */
    public const val IDLE_IN_TRANSACTION_TIMEOUT: String = "idle_in_transaction_timeout"
    /** Handshake: wrong protocol name. */
    public const val PROTOCOL_NAME: String = "protocol_name"
    /** Handshake: unsupported major version. */
    public const val PROTOCOL_VERSION: String = "protocol_version"
    /** A capability this SDK needs was not granted in the handshake. */
    public const val FEATURE_NOT_GRANTED: String = "feature_not_granted"
    /** The work ran past its deadline. */
    public const val TIMEOUT: String = "timeout"
    /** The work was cancelled. */
    public const val CANCELLED: String = "cancelled"
    /** The capability exists in the protocol but not in this server build. */
    public const val NOT_IMPLEMENTED: String = "not_implemented"
    /** Authentication was refused. */
    public const val AUTH_FAILED: String = "auth_failed"
}

/**
 * Base of every failure this SDK raises.
 *
 * [code] is the server's stable reason when it sent one. [leaderHint] is a `host:port` present only
 * with [ErrorCodes.NOT_LEADER]. The SDK never follows a redirect on its own: see [isRedirect].
 */
public sealed class TriCoreException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The machine-readable reason, or `null` when none was sent. */
    public abstract val code: String?

    /** The `host:port` of the leader for a [ErrorCodes.NOT_LEADER] refusal, when the cluster knows one. */
    public open val leaderHint: String? get() = null

    /**
     * True when the request was right but reached the wrong node (`not_leader`).
     *
     * The SDK does not re-send the request: the hinted address may be unreachable from here, a new
     * connection must authenticate again, and an open session transaction cannot move nodes.
     * A `null` [leaderHint] means there is nowhere to go yet: wait and retry.
     */
    public val isRedirect: Boolean get() = code == ErrorCodes.NOT_LEADER
}

/** The server received the request and did not complete it (`status` was not `ok`). The connection stays usable. */
public class ServerException(
    message: String,
    override val code: String?,
    override val leaderHint: String?,
    /** The response status, `error` or `not_implemented`. */
    public val status: String,
    /** The full response, for diagnostics. */
    public val response: Response,
) : TriCoreException(message)

/** An ERROR frame, or a violation of the wire protocol. Unless noted otherwise the connection is closed. */
public class ProtocolException(message: String, override val code: String?, cause: Throwable? = null) :
    TriCoreException(message, cause)

/** Authentication was refused, by an ERROR frame or by `AUTH_OK` carrying `ok: false`. */
public class AuthException(message: String, override val code: String? = ErrorCodes.AUTH_FAILED) :
    TriCoreException(message)

/** The transport failed: connect refused, socket closed, or the connection was already closed. */
public class ConnectionException(message: String, cause: Throwable? = null) : TriCoreException(message, cause) {
    override val code: String? get() = null
}

/**
 * A connect or read deadline passed. The connection is closed, because the late reply could otherwise be read
 * as the answer to the next request.
 */
public class TriCoreTimeoutException(message: String, cause: Throwable? = null) : TriCoreException(message, cause) {
    override val code: String get() = ErrorCodes.TIMEOUT
}

/** The server did not grant a capability this call needs, so the call was not sent. */
public class FeatureNotGrantedException(
    /** The capability that was missing. */
    public val feature: Feature,
    message: String,
) : TriCoreException(message) {
    override val code: String get() = ErrorCodes.FEATURE_NOT_GRANTED
}

/** No pooled connection became available in time. */
public class PoolTimeoutException(message: String) : TriCoreException(message) {
    override val code: String get() = ErrorCodes.TIMEOUT
}

/** A pooled callback broke a pool rule, such as returning with a session transaction still open. */
public class PoolMisuseException(message: String) : TriCoreException(message) {
    override val code: String? get() = null
}

/** The response did not have the shape this operation expects (for example `Rows` where `Json` was expected). */
public class UnexpectedResponseException(message: String) : TriCoreException(message) {
    override val code: String? get() = null
}
