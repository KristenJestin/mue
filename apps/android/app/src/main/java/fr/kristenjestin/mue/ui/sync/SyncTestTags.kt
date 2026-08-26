package fr.kristenjestin.mue.ui.sync

/**
 * Handles for the Compose tests.
 *
 * They exist for the nodes a test cannot address by their visible text, and the list is short on
 * purpose. Two rules decided it, and both were learned the hard way in this codebase:
 *
 * - `onNodeWithText` matches the **semantics** string, not the glyphs, so a label broken across
 *   two lines at a doubled font scale is still found by its whole text and needs no tag.
 * - A node under [fr.kristenjestin.mue.ui.food.day.announcedAs] or `clearAndSetSemantics` is
 *   **not in the merged tree at all**: its descendants are replaced by one content description.
 *   The status line below is exactly that, so it is queried by its description or by this tag,
 *   never by the words inside it.
 */
internal object SyncTestTags {

    /** The whole `Data & sync` card in `Profile`. Announced as one line; see [STATUS_LINE]. */
    const val SECTION: String = "sync:section"

    /**
     * The state, the server and the last success, announced as a single sentence.
     *
     * The three are one fact — "connected to X, synced at Y" — and a screen reader that read them
     * as three fragments would separate the state from the server it is about. The node therefore
     * carries a content description and hides its children, which is why no test may look for
     * `Synced` *inside* it.
     */
    const val STATUS_LINE: String = "sync:statusLine"

    const val SYNC_NOW: String = "sync:syncNow"
    const val SERVER_SETTINGS: String = "sync:serverSettings"

    const val ADDRESS_FIELD: String = "sync:addressField"
    const val EMAIL_FIELD: String = "sync:emailField"
    const val PASSWORD_FIELD: String = "sync:passwordField"
    const val CONNECT_BUTTON: String = "sync:connectButton"
    const val DISCONNECT_BUTTON: String = "sync:disconnectButton"

    /** The named failure of a pairing attempt. Never absent when an attempt has failed. */
    const val PAIRING_FAILURE: String = "sync:pairingFailure"
}
