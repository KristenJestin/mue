package fr.kristenjestin.mue.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueTextField
import fr.kristenjestin.mue.ui.components.rememberMueLocale
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.ZoneId
import java.util.Locale

/**
 * `Server settings` — sync PRD 9.2's pairing and 9.3's disconnection, on the screen the
 * `Data & sync` section opens.
 *
 * ## Only the manual path
 *
 * PRD 9.2 puts the QR code first and manual entry as its stated fallback, and this build ships
 * the fallback alone. Scanning needs CameraX and ML Kit — the same pair the unbuilt food barcode
 * scanner needs — and adding that dependency twice, from two modules, in two weeks, is how a
 * project ends up with two camera stacks. The fallback is not a lesser path: it satisfies 9.2 in
 * its own words, it verifies the same certificate, it obtains the same device-scoped bearer, and
 * it is what gets a history off a phone today.
 *
 * ## Two screens in one
 *
 * Unpaired, it shows the pairing form of 9.2. Paired, it shows the server, the account, a
 * sign-in for that account, and `Disconnect server` beneath it. There is no third state and no
 * tab: the row in `sync_state` decides, and the same row decides what the section in `Profile`
 * says, so the two can never disagree.
 *
 * ## Why a paired phone has a sign-in at all
 *
 * Because it was, until now, the screen that told someone to sign in and gave them nowhere to do
 * it. A bearer the server has stopped accepting shows `Sync issue` and the server's own
 * `Sign in to synchronise.`, and the only control was `Disconnect server` — so obeying the
 * instruction meant destroying a pairing whose address and account were both still correct, then
 * rebuilding it by hand.
 *
 * The same card answers the other half of the same complaint. From a healthy pairing there was
 * no way to change the server's address either; a router hands out a new one and the certificate
 * in `certs/` is issued for an IP. Both are the same operation — sign in again, for the account
 * this phone already belongs to, at an address that may have moved — so they are one form.
 *
 * What the card deliberately does **not** offer is a second email address. PRD 9.3 forbids
 * another account's data merging into this Room store, and the cheapest way to keep that promise
 * is the one [fr.kristenjestin.mue.data.pairing.ServerPairing.reauthenticate] takes: no
 * parameter for it, no field for it. Changing account is still possible and still goes through
 * `Disconnect server` — the deliberate exit, kept, and now second rather than only.
 */
@Composable
fun ServerSettingsRoute(onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: SyncViewModel = viewModel(factory = SyncViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()

    // PRD 9.2: the password is used once and never kept. Leaving the screen — by the back
    // control, by the system gesture, or because the tab changed — is what empties the field,
    // and a `DisposableEffect` is the one hook that fires for all three.
    //
    // Entering it fills the address in from `sync_state` when there is one, so a paired phone
    // shows where it is connected rather than an empty box that has to be retyped correctly
    // before a password will do anything.
    DisposableEffect(viewModel) {
        viewModel.onEnterSettings()
        onDispose { viewModel.onLeaveSettings() }
    }

    ServerSettingsScreen(
        state = state,
        form = form,
        onNavigateBack = onNavigateBack,
        onAddressChange = viewModel::onAddressChange,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onConnect = viewModel::connect,
        onSignInAgain = viewModel::signInAgain,
        onRequestDisconnect = viewModel::requestDisconnect,
        onCancelDisconnect = viewModel::cancelDisconnect,
        onConfirmDisconnect = viewModel::confirmDisconnect,
        modifier = modifier,
    )
}

@Composable
internal fun ServerSettingsScreen(
    state: DataSyncUiState,
    form: PairingFormState,
    onNavigateBack: () -> Unit,
    onAddressChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onSignInAgain: () -> Unit,
    onRequestDisconnect: () -> Unit,
    onCancelDisconnect: () -> Unit,
    onConfirmDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    locale: Locale = rememberMueLocale(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val spacing = MueTheme.spacing
    val colors = MueTheme.colors
    val focusManager = LocalFocusManager.current

    MueSubScreenScaffold(
        title = SyncMessages.SETTINGS_TITLE,
        onNavigateBack = onNavigateBack,
        navigationIcon = {
            MueIcon(MueIcons.ARROW_LEFT, tint = MueTheme.colors.textSecondary, size = 18.dp)
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            Spacer(Modifier.height(spacing.md))

            DataSyncSection(
                state = state,
                // Both null, so neither button is drawn. They used to be drawn with `{}` behind
                // them, which produced a `Server settings` button on the `Server settings`
                // screen: the one control on the page named after what someone opening it has
                // come to do, and it did nothing. `Sync now` was the same mistake, quieter — the
                // app's `Sync now` is the one in `Profile`, and a copy that cannot run is not a
                // second one.
                //
                // The section is still repeated, because its other half is worth repeating: the
                // state, the server, the date and the counts, read from the same row that decides
                // what `Profile` says, so the two can never disagree.
                onSyncNow = null,
                onOpenServerSettings = null,
                locale = locale,
                zone = zone,
            )

            if (state.connected) {
                // The order is the answer to "what can I do about this": restore or move the
                // pairing first, give it up second. `Disconnect server` stays — PRD 9.3 requires
                // it and it is the only path to another account — but it stops being the only
                // door out of a state the user did not choose.
                ConnectedServerCard(
                    state = state,
                    form = form,
                    onAddressChange = onAddressChange,
                    onPasswordChange = onPasswordChange,
                    onSignInAgain = {
                        focusManager.clearFocus()
                        onSignInAgain()
                    },
                )
                DisconnectCard(form = form, onRequestDisconnect = onRequestDisconnect)
            } else {
                ConnectCard(
                    state = state,
                    form = form,
                    onAddressChange = onAddressChange,
                    onEmailChange = onEmailChange,
                    onPasswordChange = onPasswordChange,
                    onConnect = {
                        focusManager.clearFocus()
                        onConnect()
                    },
                )
            }

            form.success?.let { message ->
                MueText(
                    text = message,
                    style = MueTheme.typography.caption,
                    color = colors.accent,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Spacer(Modifier.height(spacing.xxxl))
        }
    }

    if (form.disconnectConfirmationVisible) {
        DisconnectDialog(
            serverName = state.serverName.orEmpty(),
            onConfirm = onConfirmDisconnect,
            onDismiss = onCancelDisconnect,
        )
    }
}

/**
 * The form of PRD 9.2's fallback.
 *
 * The account hint above it is the discoverable half of PRD 9.3's rule: this phone remembers
 * which account its data belongs to, across a disconnect, and saying so *before* the sign-in is
 * what stops somebody meeting [fr.kristenjestin.mue.data.pairing.PairingFailure.DifferentAccount]
 * as a surprise at the end of a form.
 */
@Composable
private fun ConnectCard(
    state: DataSyncUiState,
    form: PairingFormState,
    onAddressChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    val spacing = MueTheme.spacing
    val colors = MueTheme.colors
    val focusManager = LocalFocusManager.current

    MueSurfaceCard(shape = MueTheme.shapes.field, contentPadding = PaddingValues(spacing.lg)) {
        MueText(SyncMessages.CONNECT_TITLE, MueTheme.typography.sectionTitle)
        MueText(
            text = SyncMessages.CONNECT_BODY,
            style = MueTheme.typography.caption,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = spacing.xs),
        )

        state.account?.let { account ->
            MueText(
                text = "The data on this phone is already synchronised with $account. " +
                    "Sign in as $account to carry on.",
                style = MueTheme.typography.caption,
                color = colors.textPrimary,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }

        Column(
            modifier = Modifier.padding(top = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            MueTextField(
                label = SyncMessages.ADDRESS_LABEL,
                value = form.address,
                onValueChange = onAddressChange,
                modifier = Modifier.testTag(SyncTestTags.ADDRESS_FIELD),
                placeholder = SyncMessages.ADDRESS_PLACEHOLDER,
                enabled = !form.busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    // A hostname is never capitalised and never auto-corrected; on a phone
                    // keyboard both would silently change what the user typed.
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
            )

            MueTextField(
                label = SyncMessages.EMAIL_LABEL,
                value = form.email,
                onValueChange = onEmailChange,
                modifier = Modifier.testTag(SyncTestTags.EMAIL_FIELD),
                enabled = !form.busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
            )

            MueTextField(
                label = SyncMessages.PASSWORD_LABEL,
                value = form.password,
                onValueChange = onPasswordChange,
                modifier = Modifier.testTag(SyncTestTags.PASSWORD_FIELD),
                enabled = !form.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        onConnect()
                    },
                ),
            )
        }

        MuePrimaryButton(
            label = if (form.connecting) SyncMessages.CONNECTING else SyncMessages.CONNECT_ACTION,
            onClick = onConnect,
            modifier = Modifier
                .padding(top = spacing.md)
                .testTag(SyncTestTags.CONNECT_BUTTON),
            enabled = !form.busy,
        )

        // The named failure. It carries `error()` as well as a live region so TalkBack announces
        // it and reports it as the field group's error rather than as a stray sentence.
        form.failure?.let { message ->
            MueText(
                text = message,
                style = MueTheme.typography.caption,
                color = colors.error,
                modifier = Modifier
                    .padding(top = spacing.sm, start = 4.dp, end = 4.dp)
                    .testTag(SyncTestTags.PAIRING_FAILURE)
                    .semantics {
                        error(message)
                        liveRegion = LiveRegionMode.Assertive
                    },
            )
        }

        MueText(
            text = SyncMessages.QR_NOTE,
            style = MueTheme.typography.caption,
            color = colors.textTertiary,
            modifier = Modifier.padding(top = spacing.md),
        )
    }
}

/**
 * What this phone is connected to, and the sign-in that repairs or moves it.
 *
 * The account is a **line of text and not a field**, and that is the load-bearing detail: PRD
 * 9.3's refusal to merge two accounts is kept here by there being nothing to type. The address
 * *is* a field, because a server that changed address is the same account in a new place and
 * refusing to let it move only forces the user through `Disconnect server` to get there.
 *
 * The body sentence changes with [DataSyncUiState.sessionRejected] and nothing else does. A
 * rejected session is the one `Sync issue` this card can actually fix, so it says so; otherwise
 * the same controls read as what they also are — renew the session, or move the server.
 */
@Composable
private fun ConnectedServerCard(
    state: DataSyncUiState,
    form: PairingFormState,
    onAddressChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignInAgain: () -> Unit,
) {
    val spacing = MueTheme.spacing
    val colors = MueTheme.colors

    MueSurfaceCard(shape = MueTheme.shapes.field, contentPadding = PaddingValues(spacing.lg)) {
        MueText(SyncMessages.PAIRED_TITLE, MueTheme.typography.sectionTitle)

        state.account?.let { account ->
            MueText(
                text = "${SyncMessages.ACCOUNT_LABEL} $account",
                style = MueTheme.typography.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }

        MueText(
            text = SyncMessages.SIGN_IN_AGAIN_TITLE,
            style = MueTheme.typography.bodyStrong,
            modifier = Modifier.padding(top = spacing.lg),
        )

        MueText(
            text = if (state.sessionRejected) {
                SyncMessages.SESSION_REJECTED_BODY
            } else {
                SyncMessages.SIGN_IN_AGAIN_BODY
            },
            style = MueTheme.typography.caption,
            color = if (state.sessionRejected) colors.textPrimary else colors.textSecondary,
            modifier = Modifier
                .padding(top = spacing.xs)
                // The sentence changes underneath somebody who is looking at the card when a
                // synchronisation fails, and it is the sentence that says what to do next.
                .semantics { liveRegion = LiveRegionMode.Polite },
        )

        Column(
            modifier = Modifier.padding(top = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            MueTextField(
                label = SyncMessages.ADDRESS_LABEL,
                value = form.address,
                onValueChange = onAddressChange,
                modifier = Modifier.testTag(SyncTestTags.ADDRESS_FIELD),
                placeholder = SyncMessages.ADDRESS_PLACEHOLDER,
                enabled = !form.busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
            )

            MueTextField(
                label = SyncMessages.PASSWORD_LABEL,
                value = form.password,
                onValueChange = onPasswordChange,
                modifier = Modifier.testTag(SyncTestTags.PASSWORD_FIELD),
                enabled = !form.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSignInAgain() }),
            )
        }

        MuePrimaryButton(
            label = if (form.connecting) SyncMessages.SIGNING_IN else SyncMessages.SIGN_IN_ACTION,
            onClick = onSignInAgain,
            modifier = Modifier
                .padding(top = spacing.md)
                .testTag(SyncTestTags.SIGN_IN_BUTTON),
            enabled = !form.busy,
        )

        form.failure?.let { message ->
            MueText(
                text = message,
                style = MueTheme.typography.caption,
                color = colors.error,
                modifier = Modifier
                    .padding(top = spacing.sm, start = 4.dp, end = 4.dp)
                    .testTag(SyncTestTags.PAIRING_FAILURE)
                    .semantics {
                        error(message)
                        liveRegion = LiveRegionMode.Assertive
                    },
            )
        }

        state.account?.let { account ->
            MueText(
                text = SyncMessages.boundToAccount(account),
                style = MueTheme.typography.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = spacing.md),
            )
        }
    }
}

/**
 * PRD 9.3's exit, on its own and underneath.
 *
 * It is kept, and it is kept *second*. A disconnection is the only way to hand this phone to
 * another account and the only way to stop synchronising, so removing it would trade one dead
 * end for another; but it is now the answer to "I want to stop", not the answer to "the server
 * refused my session", which is what it had become by being the sole control on the card.
 */
@Composable
private fun DisconnectCard(form: PairingFormState, onRequestDisconnect: () -> Unit) {
    val spacing = MueTheme.spacing
    val colors = MueTheme.colors

    MueSurfaceCard(shape = MueTheme.shapes.field, contentPadding = PaddingValues(spacing.lg)) {
        MueText(
            text = SyncMessages.DISCONNECT_BODY,
            style = MueTheme.typography.caption,
            color = colors.textSecondary,
        )

        MueSecondaryButton(
            label = if (form.disconnecting) {
                SyncMessages.DISCONNECTING
            } else {
                SyncMessages.DISCONNECT_ACTION
            },
            onClick = onRequestDisconnect,
            modifier = Modifier
                .padding(top = spacing.md)
                .testTag(SyncTestTags.DISCONNECT_BUTTON),
            enabled = !form.busy,
            contentColor = colors.error,
        )
    }
}

/**
 * PRD 9.3's confirmation.
 *
 * The body says what is *not* deleted before it says anything else, because that is the only
 * question anyone asks of a button called `Disconnect server`, and getting it wrong is what an
 * owner who has just lost a history is afraid of. A Material `AlertDialog` for the same reason
 * the activity deletion uses one: modal, blocking, focus-trapped and announced already.
 */
@Composable
private fun DisconnectDialog(serverName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = MueTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { MueText(SyncMessages.DISCONNECT_TITLE, MueTheme.typography.sectionTitle) },
        text = {
            MueText(
                text = if (serverName.isBlank()) {
                    SyncMessages.DISCONNECT_BODY
                } else {
                    "${SyncMessages.DISCONNECT_BODY} This phone will stop synchronising with " +
                        "$serverName."
                },
                style = MueTheme.typography.body,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                MueText(
                    text = SyncMessages.DISCONNECT_CONFIRM,
                    style = MueTheme.typography.button,
                    color = colors.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                MueText(SyncMessages.CANCEL, MueTheme.typography.button, color = colors.textSecondary)
            }
        },
        containerColor = colors.canvasElevated,
        shape = MueTheme.shapes.card,
    )
}

@Composable
private fun SettingsPreview(state: DataSyncUiState, form: PairingFormState) {
    MueTheme {
        ServerSettingsScreen(
            state = state,
            form = form,
            onNavigateBack = {},
            onAddressChange = {},
            onEmailChange = {},
            onPasswordChange = {},
            onConnect = {},
            onSignInAgain = {},
            onRequestDisconnect = {},
            onCancelDisconnect = {},
            onConfirmDisconnect = {},
            locale = Locale.UK,
            zone = ZoneId.of("Europe/Paris"),
        )
    }
}

@Preview(name = "Server settings — connect", widthDp = 390, heightDp = 1100)
@Composable
private fun ServerSettingsConnectPreview() {
    SettingsPreview(
        state = DataSyncUiState(),
        form = PairingFormState(address = "https://mue.home.arpa", email = "kris@example.org"),
    )
}

@Preview(name = "Server settings — refused", widthDp = 390, heightDp = 1100)
@Composable
private fun ServerSettingsFailurePreview() {
    SettingsPreview(
        state = DataSyncUiState(account = "kris@example.org"),
        form = PairingFormState(
            address = "https://mue.home.arpa",
            email = "someone@example.org",
            failure = "The data on this phone is already synchronised with kris@example.org.",
        ),
    )
}

@Preview(name = "Server settings — paired", widthDp = 390, heightDp = 1400)
@Composable
private fun ServerSettingsPairedPreview() {
    SettingsPreview(
        state = DataSyncUiState(
            status = SyncStatus.SYNCED,
            serverName = "mue.home.arpa",
            account = "kris@example.org",
            lastSuccessAt = 1_756_240_000_000L,
        ),
        form = PairingFormState(address = "https://mue.home.arpa"),
    )
}

/** The state this screen was rebuilt for: paired, correct, and refused. */
@Preview(name = "Server settings — session rejected", widthDp = 390, heightDp = 1400)
@Composable
private fun ServerSettingsSessionRejectedPreview() {
    SettingsPreview(
        state = DataSyncUiState(
            status = SyncStatus.SYNC_ISSUE,
            serverName = "192.168.1.100:3000",
            account = "kris@mue.home.arpa",
            lastSuccessAt = 1_756_240_000_000L,
            outstandingChanges = 1,
            lastErrorMessage = "Sign in to synchronise.",
            sessionRejected = true,
        ),
        form = PairingFormState(address = "https://192.168.1.100:3000"),
    )
}
