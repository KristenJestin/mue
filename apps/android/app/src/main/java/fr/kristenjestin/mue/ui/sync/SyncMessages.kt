package fr.kristenjestin.mue.ui.sync

import fr.kristenjestin.mue.data.pairing.DisconnectResult
import fr.kristenjestin.mue.data.sync.SyncOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Every word `Data & sync` and `Server settings` say, in one file, and the three functions that
 * turn a machine outcome into one of them.
 *
 * The functions matter more than the constants. `SyncOutcome` has four branches and
 * `Completed` alone carries six counters; a screen that rendered "Sync complete" over all of
 * them would be hiding a rejected mutation behind a tick — which is what FR-SYNC-007 spends a
 * paragraph forbidding, and what an owner who has just lost a history would have no way to
 * detect.
 */
object SyncMessages {

    const val SECTION_TITLE: String = "Data & sync"
    const val SYNC_NOW: String = "Sync now"
    const val SERVER_SETTINGS: String = "Server settings"

    const val STATE_NOT_CONNECTED: String = "Not connected"
    const val STATE_SYNCED: String = "Synced"
    const val STATE_CHANGES_PENDING: String = "Changes pending"
    const val STATE_SYNC_ISSUE: String = "Sync issue"

    const val NO_SERVER: String = "No server"
    const val NEVER_SYNCED: String = "Never synchronised"

    /**
     * The one sentence the section says when nothing is paired.
     *
     * PRD 9.1: "L'absence de serveur associé n'affiche aucune alerte sur les écrans principaux."
     * So it is stated once, here, in the section that exists to answer the question — and Entry,
     * Progress, Activity and Food are left with nothing at all.
     */
    const val NOT_CONNECTED_BODY: String =
        "This phone keeps everything you record, and nothing has left it. Connect your private " +
            "Mue server to keep a second copy and reach it from a computer."

    // --- `Server settings` -------------------------------------------------------------------

    const val SETTINGS_TITLE: String = "Server settings"
    const val CONNECT_TITLE: String = "Connect a server"
    const val CONNECT_BODY: String =
        "Enter the private HTTPS address of your Mue server, then sign in with your Mue account. " +
            "Your password is used once, to obtain a session for this phone, and is never stored."
    const val ADDRESS_LABEL: String = "Server address"
    const val ADDRESS_PLACEHOLDER: String = "https://mue.home.arpa"
    const val EMAIL_LABEL: String = "Email address"
    const val PASSWORD_LABEL: String = "Password"
    const val CONNECT_ACTION: String = "Connect"
    const val CONNECTING: String = "Connecting…"

    /** PRD 9.2 keeps the QR code as the primary path; this build ships its documented fallback. */
    const val QR_NOTE: String =
        "Scanning the QR code your server prints is not available in this version. Typing the " +
            "address does the same thing."

    const val PAIRED_TITLE: String = "Connected server"
    const val ACCOUNT_LABEL: String = "Signed in as"

    // --- signing in again, without giving the pairing up ---------------------------------------

    /**
     * PRD 9.3's "se reconnecter au même compte reprend la synchronisation", as words on a screen.
     *
     * Until this existed, a phone whose bearer the server had stopped accepting was shown
     * `Sync issue`, the server's own `Sign in to synchronise.`, and one button: `Disconnect
     * server`. The only way to obey the instruction was to throw away a pairing that was correct
     * in every respect but its token, and retype an address, an email and a password to rebuild
     * the same row. That is not a recovery, it is a dead end with a long way round.
     */
    const val SIGN_IN_AGAIN_TITLE: String = "Sign in again"
    const val SIGN_IN_ACTION: String = "Sign in"
    const val SIGNING_IN: String = "Signing in…"

    /**
     * Said when the pairing is healthy, where it reads as what it is: the way to move a server,
     * not a fault report. A home router reassigns an address and the certificate in `certs/` is
     * issued for one; before this, changing it meant `Disconnect server` first.
     */
    const val SIGN_IN_AGAIN_BODY: String =
        "Renew this phone's session, or point it at a new address if your server has moved. " +
            "Your password is used once, as it was the first time, and is never stored."

    /** Said when the server has refused this phone's session, in the card that can restore it. */
    const val SESSION_REJECTED_BODY: String =
        "The server refused this phone's session, so nothing is being sent or received. Sign in " +
            "again to restore it: the server, the account, the queue and everything recorded " +
            "here are kept."

    /**
     * PRD 9.3's rule, stated on the one screen that could otherwise be mistaken for a way around
     * it — and it names `Disconnect server` because that control is right underneath.
     */
    fun boundToAccount(account: String): String =
        "Mue signs in here as $account, because that is the account this phone's data belongs " +
            "to. To use a different one, disconnect this server first — connecting another " +
            "account never merges it with what is already here."

    const val DISCONNECT_ACTION: String = "Disconnect server"
    const val DISCONNECT_TITLE: String = "Disconnect this server?"
    const val DISCONNECT_BODY: String =
        "Nothing recorded on this phone is deleted. Changes that have not been sent stay in the " +
            "queue and go out if you connect the same account again."
    const val DISCONNECT_CONFIRM: String = "Disconnect"
    const val CANCEL: String = "Cancel"
    const val DISCONNECTING: String = "Disconnecting…"

    // --- derived lines -------------------------------------------------------------------------

    fun label(status: SyncStatus): String = when (status) {
        SyncStatus.NOT_CONNECTED -> STATE_NOT_CONNECTED
        SyncStatus.SYNCED -> STATE_SYNCED
        SyncStatus.CHANGES_PENDING -> STATE_CHANGES_PENDING
        SyncStatus.SYNC_ISSUE -> STATE_SYNC_ISSUE
    }

    /** PRD 9.1's "date et heure", in the phone's own language and format. */
    fun lastSync(epochMillis: Long?, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
        val instant = epochMillis ?: return NEVER_SYNCED
        val formatted = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone)
            .format(Instant.ofEpochMilli(instant))
        return "Last synced $formatted"
    }

    /** PRD 9.1 shows the count only when it is not zero, so this returns null when it is. */
    fun outstanding(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 change waiting to be sent"
        else -> "$count changes waiting to be sent"
    }

    /** FR-SYNC-007: kept, marked, and named — never quietly folded into the number above. */
    fun refused(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 of them was refused by the server and is still here."
        else -> "$count of them were refused by the server and are still here."
    }

    /**
     * The health-profile rows of PRD 13.4: journalled, queued, and undeliverable until
     * `packages/contracts` grows their branch. Named, because a count that never falls is
     * indistinguishable from a broken sync unless somebody says otherwise.
     */
    fun undeliverable(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 waits for a server version that can accept it. It is not lost."
        else -> "$count wait for a server version that can accept them. They are not lost."
    }

    /**
     * What one run of the engine did, in a sentence, with the branch it came from still visible.
     *
     * `Completed` is not one message. A run that pushed nothing, applied nothing and refused
     * nothing is "Everything is up to date"; a run that had a rejection in it says so and says
     * how many, because that rejection is the difference between `Synced` and `Sync issue` and
     * the user is the only one who can act on it.
     */
    fun describe(outcome: SyncOutcome): SyncNote = when (outcome) {
        SyncOutcome.NotPaired -> SyncNote(
            "No server is connected, so there was nothing to synchronise.",
            isProblem = false,
        )

        is SyncOutcome.UpgradeRequired -> SyncNote(
            "This version of Mue cannot read what the server sent, so nothing was changed on " +
                "this phone. Update the app. (${outcome.error.message})",
            isProblem = true,
        )

        is SyncOutcome.Failed -> SyncNote(
            if (outcome.retryable) {
                "${outcome.message} Mue will try again by itself."
            } else {
                "${outcome.message} (${outcome.code})"
            },
            isProblem = true,
        )

        is SyncOutcome.Completed -> completed(outcome)
    }

    private fun completed(outcome: SyncOutcome.Completed): SyncNote {
        if (outcome.hasIssues) {
            val parts = buildList {
                if (outcome.rejected > 0) add("${outcome.rejected} refused by the server")
                if (outcome.unreadable > 0) add("${outcome.unreadable} unreadable on this phone")
            }
            return SyncNote(
                "Synchronised, but ${parts.joinToString(" and ")}. Nothing was deleted.",
                isProblem = true,
            )
        }

        val sent = outcome.applied + outcome.duplicates
        val message = when {
            outcome.moreAvailable ->
                "Synchronised so far: ${plural(sent, "change")} sent, " +
                    "${plural(outcome.changes, "change")} received. More is still coming."

            // "Up to date" is a claim about the queue, not about the exchange. A run that sent
            // and received nothing *because there was nothing to send* is up to date; a run that
            // sent nothing while rows sit in the outbox untried is not, and saying so beside a
            // `1 change waiting` counter reads as a contradiction — which is exactly how it was
            // reported. `deferred` rows are the health profile of PRD 13.4, held back because the
            // contract has no branch for them yet; they are not a fault and must not be described
            // as one, but they are also not "up to date".
            sent == 0 && outcome.changes == 0 && outcome.deferred > 0 ->
                "Nothing to exchange. ${plural(outcome.deferred, "change")} " +
                    "${if (outcome.deferred == 1) "is" else "are"} waiting for a server that " +
                    "understands it, and will go out on its own once one does."

            sent == 0 && outcome.changes == 0 -> "Everything is already up to date."

            else -> "Synchronised: ${plural(sent, "change")} sent, " +
                "${plural(outcome.changes, "change")} received."
        }
        return SyncNote(message, isProblem = false)
    }

    /** PRD 9.3's two endings, told apart, because only one of them leaves work to do. */
    fun describe(result: DisconnectResult): SyncNote = when (result) {
        DisconnectResult.NotPaired -> SyncNote("No server was connected.", isProblem = false)

        is DisconnectResult.Revoked -> SyncNote(
            "Disconnected from ${result.serverName}. The session was revoked and nothing " +
                "recorded on this phone was deleted.",
            isProblem = false,
        )

        is DisconnectResult.LocalOnly -> SyncNote(
            "Disconnected from ${result.serverName}. It could not be reached, so this phone's " +
                "session is still open on the server — revoke it from the server itself when " +
                "you can. Nothing recorded here was deleted.",
            isProblem = true,
        )
    }

    private fun plural(count: Int, noun: String): String =
        if (count == 1) "1 $noun" else "$count ${noun}s"
}
