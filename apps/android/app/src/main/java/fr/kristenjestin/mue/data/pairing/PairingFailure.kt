package fr.kristenjestin.mue.data.pairing

/**
 * Why a pairing did not happen — one case per cause, never one case for all of them.
 *
 * Sync PRD 9.2 is a chain of four things that can each go wrong on their own: an address that is
 * not an address, a host that does not answer, a certificate Android will not trust, and a
 * password the server refuses. Someone who has just typed `https://mue.home.arpa` into a phone
 * has no way to tell those apart from the outside, and "something went wrong" leaves them
 * retyping a password that was already right. So every branch below is a branch the user can act
 * on differently, and [message] says which one it was, in the words of the thing they typed.
 *
 * The vocabulary is deliberately the same shape as `LookupFailure`, which made the same decision
 * for the Open Food Facts lookup (PRD_FOOD 17): named reasons rather than a nullable result,
 * because a nullable result collapses "not found" into "offline" and tells someone to type a
 * product in because a train went into a tunnel.
 *
 * Nothing here is persisted. A failure lives as long as one attempt at the pairing form; what
 * survives a *paired* server's later trouble is `sync_state.last_error_code` and
 * `last_error_message`, which the sync engine writes and `Data & sync` reads.
 */
sealed interface PairingFailure {

    /** One line, addressed to the person at the keyboard, naming what to change. */
    val message: String

    // --- the address ------------------------------------------------------------------------

    /** Nothing was typed. */
    data object AddressMissing : PairingFailure {
        override val message: String = "Enter the HTTPS address of your Mue server."
    }

    /** Typed, but not an address at all. */
    data class MalformedAddress(val input: String) : PairingFailure {
        override val message: String =
            "\"$input\" is not a web address. It should look like https://mue.home.arpa."
    }

    /**
     * `http://`, explicitly. Sync PRD 16 encrypts Android-server traffic without exception, and
     * a private network is not a substitute for it — the PRD says so in as many words. Silently
     * upgrading the scheme would be worse than refusing: it would claim a guarantee the user did
     * not ask for and could not check.
     */
    data class InsecureScheme(val input: String) : PairingFailure {
        override val message: String =
            "Mue only connects over HTTPS. Replace http:// with https:// in \"$input\"."
    }

    // --- reaching the host ------------------------------------------------------------------

    /** DNS said there is no such name. Almost always a typo, or a private resolver not in use. */
    data class HostNotFound(val host: String) : PairingFailure {
        override val message: String =
            "\"$host\" could not be found. Check the spelling, and that this phone is on the " +
                "network that resolves it."
    }

    /** The name resolved and nothing answered: away from home, server off, wrong port. */
    data class Unreachable(val host: String) : PairingFailure {
        override val message: String =
            "\"$host\" did not answer. A Mue server is reachable only from your own network, so " +
                "check that this phone is on it and that the server is running."
    }

    /**
     * TLS refused. Kept apart from [Unreachable] because it is the one failure where the phone
     * *did* reach something and deliberately would not talk to it — sync PRD 16 prefers a
     * certificate issued by a DNS challenge to a local authority installed on every client, and
     * this is the message that tells the owner which of the two is still to be done.
     */
    data class UntrustedCertificate(val host: String, val detail: String?) : PairingFailure {
        override val message: String = buildString {
            append("Android does not trust the certificate \"$host\" presented. ")
            append("Mue will not send your data over a connection it cannot verify.")
            if (!detail.isNullOrBlank()) append(" ($detail)")
        }
    }

    /** Something answered, and it was not Mue: a router page, a proxy, another service. */
    data class NotAMueServer(val host: String, val detail: String?) : PairingFailure {
        override val message: String = buildString {
            append("Something answered at \"$host\", but it is not a Mue server.")
            if (!detail.isNullOrBlank()) append(" $detail")
        }
    }

    /** A Mue server that is up but not ready — a database still starting, a failed probe. */
    data class ServerUnavailable(val host: String, val status: Int) : PairingFailure {
        override val message: String =
            "\"$host\" is running but not ready to answer yet (HTTP $status). Try again shortly."
    }

    // --- signing in ---------------------------------------------------------------------------

    data object CredentialsMissing : PairingFailure {
        override val message: String = "Enter the email address and password of your Mue account."
    }

    /**
     * The server rejected the pair. Deliberately says nothing about *which* of the two was
     * wrong, because the server does not say either (PRD 15.3: a refused attempt reveals no
     * data).
     */
    data object CredentialsRejected : PairingFailure {
        override val message: String =
            "That email address and password were refused. Nothing on this phone was changed."
    }

    /** PRD 16: the server limits attempts. Waiting is the only thing that helps. */
    data class TooManyAttempts(val retryAfterSeconds: Long?) : PairingFailure {
        override val message: String = buildString {
            append("The server refused this attempt because there have been too many. ")
            append(
                retryAfterSeconds
                    ?.let { "Try again in about ${it.coerceAtLeast(1)} seconds." }
                    ?: "Try again in a few minutes.",
            )
        }
    }

    /** A 4xx the server explained itself: an account disabled, an unverified email, a policy. */
    data class SignInRefused(val code: String?, val detail: String) : PairingFailure {
        override val message: String = "The server refused the sign-in: $detail"
    }

    /**
     * The sign-in endpoint is not there. A Mue server old enough to predate email-and-password
     * sign-in, or a build with the plugin switched off — and not a wrong password, which is why
     * it is not [CredentialsRejected].
     */
    data class SignInUnsupported(val host: String) : PairingFailure {
        override val message: String =
            "\"$host\" does not offer email-and-password sign-in. It is either too old for this " +
                "version of Mue, or it is not the Mue server you meant."
    }

    /**
     * Signed in, and the server sent no bearer back. Better Auth returns it in `set-auth-token`;
     * a server whose bearer plugin is off authenticates a browser and leaves the phone with
     * nothing to present.
     */
    data class NoSessionToken(val host: String) : PairingFailure {
        override val message: String =
            "\"$host\" accepted the sign-in but issued no device session, so this phone has " +
                "nothing to authenticate with. Nothing was stored."
    }

    /**
     * The bearer came back and the server would not accept it on the very next request. Checked
     * on purpose rather than assumed: storing a token that does not work would produce a paired
     * server that fails at every later sync, for a reason nobody could see from the screen.
     */
    data class SessionRejected(val host: String) : PairingFailure {
        override val message: String =
            "The session \"$host\" issued was refused on the next request, so it was not kept."
    }

    // --- whose data is on this phone ----------------------------------------------------------

    /**
     * Sync PRD 9.3: "connecter un autre compte ne fusionne jamais silencieusement ses données
     * avec le stockage Room existant."
     *
     * The merge is not hypothetical. Every local row that has never been acknowledged sits in the
     * outbox, and the first synchronisation after a pairing sends the outbox — so signing in as
     * somebody else would upload this phone's history into their account and pull theirs down
     * into this Room file. Both halves are silent and neither is undoable.
     *
     * So the pairing stops here, before a single byte is stored, and the session the server has
     * just minted is revoked on the way out.
     */
    data class DifferentAccount(val storedAccount: String, val offeredAccount: String) :
        PairingFailure {
        override val message: String =
            "The data on this phone is already synchronised with $storedAccount. Signing in as " +
                "$offeredAccount would mix two accounts together, so nothing was connected. " +
                "Sign in as $storedAccount to carry on."
    }

    // --- the phone itself ---------------------------------------------------------------------

    /** Keystore or Room refused to keep what was obtained. Never a half-stored pairing. */
    data class NotStored(val detail: String?) : PairingFailure {
        override val message: String = buildString {
            append("This phone could not store the session it was given, so it was not kept.")
            if (!detail.isNullOrBlank()) append(" ($detail)")
        }
    }
}
