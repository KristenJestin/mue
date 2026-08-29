import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
}

/*
 * The address a `beta` build arrives with in its pairing form, read from `local.properties`.
 *
 * From that file and never from this one, because the value is a machine's IP address. The owner
 * runs the development server on his PC and reinstalls the beta often, so retyping
 * `http://192.168.1.100:3000` into `Server settings` at every install is a chore that teaches
 * nobody anything — but the address belongs to a DHCP lease, not to a repository whose `develop`
 * is merged into `main`. `local.properties` is already the machine-local file that carries
 * `sdk.dir`, already git-ignored, and already listed in AGENTS.md §9.4 among the things a fresh
 * worktree has to be given by hand.
 *
 * **Absent, it is the empty string, and that is the whole of the fallback.** `SyncViewModel`
 * treats an empty default as no default, so somebody who clones this repository and writes only
 * `sdk.dir` builds a beta that behaves exactly as it did before this existed: an empty field, no
 * message, nothing to configure before the project compiles.
 *
 * `providers.fileContents` and not `File.readText`, because the configuration cache is on
 * (`gradle.properties`): read this way the file is a declared build input, so editing the address
 * invalidates the cached configuration instead of being ignored until some unrelated change
 * happens to rebuild it. `java.util.Properties` and not a hand-rolled split, because that is what
 * reads `sdk.dir` out of the same file, escapes and all.
 */
val localProperties: Provider<Properties> = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(StringReader(text)) } }

/**
 * One key of that file, trimmed, and the empty string when either the file or the key is absent.
 *
 * Factored out when the second key arrived rather than copied: the two have to agree on what
 * "absent" means down to the trimming, because both resolve to the same empty string that
 * `defaultConfig` already declares and that `SyncViewModel` reads as *no default at all*. Two
 * copies of that could drift by one `.trim()` and the difference would show up as a beta whose
 * email box holds a space.
 */
fun localProperty(name: String): String =
    localProperties.map { it.getProperty(name).orEmpty().trim() }.getOrElse("")

val betaDefaultServerAddress: String = localProperty("mue.beta.server")

/*
 * The account that same `beta` build arrives with in its email box.
 *
 * Same file, same variant, same reasoning as the address: the owner recreates `mue_dev` with
 * `docker compose down -v` and reinstalls the beta often, and this client has no sign-up screen
 * (AGENTS.md §4.6), so every round trip costs him an address, an email and a password retyped on
 * a phone keyboard. Two of those three name things — *which machine*, *which account* — and one
 * is a secret.
 *
 * **That third key now exists, and the line this note used to draw has moved.** It said there
 * would be no key for the password, and the reason it gave was that `resValue` compiles its
 * argument into `res/values/values.xml` inside the APK, that an APK is a file that gets copied
 * onto a phone, kept in `../` beside the PRDs and sent over a chat to be installed, and that a
 * password is the one value whose whole worth is that nobody else holds it. The first half of
 * that is still exactly true — the string *is* readable in the artefact, and
 * `betaDefaultAccountPassword` below names the command that reads it. What was re-argued and
 * decided differently is the second half: the account these two keys name is a throwaway on a
 * development database, behind a server that answers on nobody's network but the owner's, so
 * what an APK gives away opens nothing whoever holds it could reach. Read that note before
 * setting the key — the arbitration holds only under the condition it states.
 *
 * The seeding command on the server side refuses a password argument and keeps refusing it,
 * which is a different rule and not a weaker version of this one: `scripts/admin.ts accounts
 * create` takes the password from `MUE_ACCOUNT_PASSWORD` or from a prompt because an argument
 * lands in the shell history, in `argv`, and therefore in `ps` and the task manager — files and
 * process tables on the *development machine*, which the arbitration below says nothing about.
 */
val betaDefaultAccountEmail: String = localProperty("mue.beta.email")

/*
 * The password that same `beta` build arrives with in its third box: the one value here that is a
 * credential, and the only one whose note has to be read before the key is set.
 *
 * **It travels into the APK in clear, and that is the decision rather than an oversight.**
 * `resValue` puts it in the resource table and nothing downstream hides it: R8 renames classes
 * and never touches `res/values`, and `isShrinkResources` removes resources that are unused
 * rather than obscuring the ones that are used. It was checked on the artefact, not assumed —
 *
 *     aapt2 dump resources app-beta.apk
 *
 * prints `string/default_account_password` with its value spelled out beside it, and that is the
 * command that proved it. Anyone holding a beta APK reads the password out of it in one step.
 *
 * The owner weighed that and took it, and the argument is about what the secret defends rather
 * than about how well it is hidden. The account `mue.beta.email` names is disposable: it lives on
 * `mue_dev`, a database `docker compose down -v` destroys and `scripts/admin.ts accounts create`
 * refills, and the server holding it runs on his own machine, on his own home network, with no
 * hosting, no public name and nothing forwarded to it. A reader who extracts this string has
 * learned the credentials of an account he has no route to, guarding data that is recreated by a
 * command. The secret protects nothing an attacker can reach.
 *
 * **Which is the condition, and it is the whole of it: this key takes a throwaway password and
 * nothing else.** Not the owner's own, not one that also opens the production server, not one
 * reused anywhere a person or a service would accept it. The reasoning above is not that a
 * password in an APK is acceptable; it is that *this* password costs nothing when it leaks. The
 * day `mue.beta.password` holds a password that is worth something somewhere else, every line of
 * that argument is false and the disclosure is a real one — and what has to go then is the key,
 * not this note. The same day arrives if the development server ever becomes reachable from
 * outside that network, or if the beta ever pairs against anything but a disposable database.
 *
 * Bounded like the other two, and the bound is checked rather than intended: `defaultConfig`
 * declares the empty string, `beta` alone overrides it, and `verifyReleaseCarriesNoBetaDefaults`
 * below reads the release APK's own resource table back and fails the build if any of the three
 * arrives carrying a value. A claim about what is *not* in an artefact is worth what the artefact
 * says, not what this file says.
 */
val betaDefaultAccountPassword: String = localProperty("mue.beta.password")

android {
    namespace = "fr.kristenjestin.mue"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.kristenjestin.mue"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        /*
         * The launcher label and the launcher background, declared as variant properties rather
         * than as files.
         *
         * `android:label` still points at `@string/app_name`; what moved is who declares it.
         * `src/main/res/values/strings.xml` used to, and it no longer does, because keeping both
         * is worse than it looks: AGP does not reject the pair, it resolves it silently in favour
         * of the generated value. Checked, not assumed — with the `<string>` still in place,
         * `mergeDebugResources` succeeded and
         * `build/intermediates/incremental/debug/mergeDebugResources/merged.dir/values/values.xml`
         * held one `app_name`, reading `Mue Debug`. A `<string>Mue</string>` sitting in
         * `strings.xml` looking authoritative while contributing nothing is the kind of line
         * someone later edits and watches do nothing, so there is exactly one declaration and it
         * is here. The cost is `translatable="false"`, which the generator adds by itself; a
         * product name is not translated, and the day one has to be, this moves back into
         * `values/` for `main` and the build types override the label another way.
         *
         * A `manifestPlaceholder` on `android:label` would avoid the merge question entirely, and
         * is the wrong trade: it inlines a bare string into the manifest, so nothing else in the
         * app can reference the name and no qualified `values-*` folder can ever answer for it.
         *
         * `launcher_background` is the fill of `ic_launcher_background.xml`, which was the literal
         * `#101012`. Three applications side by side under one icon is a tap the owner loses, and
         * a flat colour behind the same amber is the only way to tell them apart that costs no new
         * asset: the foreground and the monochrome layer are untouched, so the drawn shape stays
         * the product's.
         *
         * Not `src/debug/res/values/colors.xml`, and that is not a matter of taste: `local` and
         * `beta` point their `res` at `src/debug` to pick up the trust store (see the source-set
         * block below), so a colour dropped in that folder to mark debug builds would follow the
         * pointer into both of them — giving `local`, which has to be indistinguishable from
         * production, the debug icon, and `beta`, which has to look like neither, the same one. A
         * `resValue` is attached to the build type and travels with nothing.
         */
        resValue("string", "app_name", "Mue")
        resValue("color", "launcher_background", "#101012")

        /*
         * Whether the build may keep a server address the owner typed as `http://`.
         *
         * PRD_SERVER_SYNC_MCP 16 admits nothing but HTTPS and `ServerAddresses.parse` has always
         * enforced it before a socket is opened. It still does here, in `defaultConfig`, which is
         * the whole design of this flag: **the default answer is no, and `release` never overrides
         * it.** A build type that says nothing about cleartext refuses cleartext, so the shipped
         * application cannot leave in clear even by an omission — and the three overrides below
         * are the exhaustive list of builds that can, each one a build signed with the debug key
         * that could never reach a store.
         *
         * The owner's server runs on his own machine, on his own network, with no hosting and no
         * public name; no authority will issue a certificate for it and he has chosen not to run
         * one of his own. He was told the cost — the bearer in `sync_state` readable by anything
         * else on that WiFi, so write access to his health data and his MCP tools — and decided.
         *
         * A `bool` resource and not `BuildConfig`, because `buildConfig` is off in this module
         * (`buildFeatures` below) while `resValues` is already on for the two declarations above.
         * `SyncContainer` reads it and hands the answer to `ServerAddresses.parse`, which takes it
         * as a parameter and stays free of `Context` — that is what keeps every rule in this file
         * provable by a JVM test rather than by an emulator.
         *
         * It is only half of the arrangement. The other half is
         * `src/debug/res/xml/network_security_config.xml`, which the platform enforces and which
         * `local` and `beta` point at; `release` has no such file and keeps Android's own default.
         * Neither half alone lets a build talk in clear, and the two are set from the same list of
         * build types on purpose.
         */
        resValue("bool", "cleartext_server_permitted", "false")

        /*
         * The address `Server settings` starts with, which for every build but one is none.
         *
         * PRD_SERVER_SYNC_MCP 9.2 has that address typed, and this does not change it: the value
         * only fills a field that would otherwise have been empty, and only where a wrong guess
         * costs nothing. Empty here, and left empty by three of the four variants, each for its
         * own reason — `release` and `local` are the application the owner carries, and a build
         * that proposed a machine on somebody's LAN would be proposing an address it cannot
         * reach; `debug` is the sandbox an instrumentation drives, where a pre-filled field is a
         * value no test asked for and every existing assertion about an empty form would have to
         * be re-read. Only `beta` overrides it, and from `local.properties`.
         *
         * A `string` resource for the reason `cleartext_server_permitted` above is a `bool` one:
         * `buildConfig` is off in this module and `resValues` is already on, so a build type
         * states such a thing as a generated resource. `SyncViewModel.Factory` is the single
         * `Context` on the path from here to the form, exactly as `SyncContainer` is for the flag
         * above — the view model itself takes the answer as a parameter and stays provable by a
         * JVM test.
         *
         * The email and the password boxes get the same treatment from `default_account_email`
         * and `default_account_password` below. The third one is not the same kind of value as
         * the first two, and the note on `betaDefaultAccountPassword` at the top of this file
         * says what it costs and under which condition it was allowed at all.
         */
        resValue("string", "default_server_address", "")

        /*
         * The email `Server settings` starts with, which for every build but one is none.
         *
         * A second declaration rather than a second field on the first: they are two resources
         * because `resValue` produces one string each, and they are two `local.properties` keys
         * because the owner may want either without the other — an address with no account is the
         * state a fresh server is in before `scripts/admin.ts accounts create` has run.
         *
         * Empty here, so `release`, `local` and `debug` are untouched by it for the reasons the
         * address's own note gives, and `beta` overrides it only when `local.properties` names a
         * value. That is what makes an unconfigured clone build the beta it built before: the
         * resource is the empty string, and `SyncViewModel` reads empty as *no default*.
         */
        resValue("string", "default_account_email", "")

        /*
         * The password `Server settings` starts with, which for every build but one is none — and
         * the declaration that matters most in this block, because the empty string here is what
         * keeps a credential out of the application the owner carries.
         *
         * `release`, `local` and `debug` never override it, and unlike the other two that is not
         * merely "nothing useful to propose". `local` *is* the daily build and `release` is what a
         * store would get; a password compiled into either would be readable in an artefact whose
         * reader can reach the server it opens, which is the exact thing the note on
         * `betaDefaultAccountPassword` says was **not** accepted. Only `beta` overrides it, only
         * from `local.properties`, and only with a throwaway.
         *
         * Declared here rather than only on `beta` for the reason the other two are: `getString`
         * has to answer for every variant, and a resource that exists in one build type and not in
         * the others is a `Resources.NotFoundException` on the three that were meant to be
         * untouched.
         */
        resValue("string", "default_account_password", "")
    }

    /*
     * Three applications on one phone, and `applicationIdSuffix` is what makes them three.
     *
     * `applicationId` is the whole of an application's identity on Android: two builds sharing one
     * share a data directory, a launcher entry and a row in `pm list packages`, so installing
     * either replaces the other and uninstalling either takes the data with it. That is not a
     * hypothesis about this project. Every variant here used to answer to `fr.kristenjestin.mue`,
     * and `connectedAndroidTest` — which installs what it tests and then uninstalls it again —
     * deleted the owner's real weight history.
     *
     *   release, local      fr.kristenjestin.mue         the application he carries
     *   beta                fr.kristenjestin.mue.beta    the pre-release he also carries
     *   debug, androidTest  fr.kristenjestin.mue.debug   everything an instrumentation may touch
     *
     * The third line is the one that had to exist. `debug` is AGP's default `testBuildType` and
     * nothing here changes it, so every instrumented test — this module's `androidTest`, and the
     * test APK built beside it — installs under `.debug` and can no longer name the daily
     * application, whatever device happens to be attached and whoever typed the command.
     *
     * The suffix is a property of the *variant* and never of the branch. Nothing in this file
     * reads git, so `main` and `develop` carry it byte for byte identical: a merge between them
     * has nothing to resolve here, and one commit built twice yields the same three package names.
     */
    buildTypes {
        /*
         * The sandbox, and the only build type a test ever reaches.
         *
         * `.debug` gives it a data directory of its own, which is what makes `connectedAndroidTest`
         * safe to run rather than merely discouraged: it may create and destroy that directory as
         * often as it likes and never touch the one holding the owner's measurements.
         */
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Mue Debug")
            // Deep green behind the amber. Unmistakable at 48 dp, and no asset was drawn for it.
            resValue("color", "launcher_background", "#1E7A47")
            // Matches the trust store it already reads: this build type owns
            // `src/debug/res/xml/network_security_config.xml`, and that file now permits
            // cleartext too. The pair has to be set together or the parser accepts an address the
            // platform then blocks, which surfaces as `Unreachable` — "it did not answer" about a
            // server that answered fine.
            resValue("bool", "cleartext_server_permitted", "true")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // An unsigned APK cannot be installed, so the minified build could never be
            // exercised on a device. `-PmueDebugSigning` signs it with the local debug key
            // for that purpose only; the store key is supplied out of band and no keystore
            // or credential belongs in this repository.
            if (providers.gradleProperty("mueDebugSigning").isPresent) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }

        /*
         * `develop`'s build: the pre-release, and the one variant whose inheritance had to be
         * argued rather than assumed.
         *
         * It initialises from `release`, and for the reason `local`'s note below spells out at
         * length: a build that is not minified is not a rehearsal of anything. R8, the resource
         * shrinker and the ProGuard rules are where a missing `keep` shows up, and a beta built
         * without them would find nothing the release build would not then find on the owner's
         * phone instead.
         *
         * It also reads `debug`'s trust store, and *that* is the arbitration. A beta is not a
         * screenshot: it is the pre-release its owner runs for real, for a week, before it becomes
         * `release`. Running it for real means synchronising, and `network_security_config.xml`
         * explains why synchronising means trusting a certificate authority that exists only on
         * his network — no public authority will ever issue for a machine on somebody's home WiFi.
         * A beta that cannot reach the server can only exercise the half of the app that was never
         * in doubt. So it takes the same `src/debug` overlay `local` takes, and the widening is
         * bounded the same way: the anchor is `src="user"`, nothing is trusted that the person
         * holding the phone did not install himself, and `release` still sees none of it.
         *
         * What it does not inherit is the store key. Signed with the debug key like `local`, so it
         * installs without a flag — a pre-release its owner cannot install is not one — and so it
         * can never be the artefact that reaches the store by accident.
         */
        create("beta") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "Mue Beta")
            // Strong blue behind the same amber: a different application at a glance, no new asset.
            resValue("color", "launcher_background", "#2D5BA8")
            // Stated here rather than inherited: `initWith(release)` copies `release`'s answer,
            // which is "no", and a beta that could not reach the server would exercise only the
            // half of the app that was never in doubt — the same argument that gave it `debug`'s
            // trust store in the first place.
            resValue("bool", "cleartext_server_permitted", "true")
            // The one variant that may arrive with an address already in the box. The value comes
            // from `local.properties` and the reasoning is on `betaDefaultServerAddress` at the
            // top of this file; when the key is absent this is the empty string `defaultConfig`
            // already declares, so a clone that configures nothing builds the beta it built
            // before. It pairs with the two lines above rather than standing alone: an address
            // proposed to a build that would refuse it at the keyboard would be a worse offer
            // than none.
            resValue("string", "default_server_address", betaDefaultServerAddress)
            // The account that goes with it, under the same terms and from the same file: absent
            // key, empty string, a beta that behaves exactly as it did before. The three keys are
            // read independently, so configuring one and not the others is a supported state and
            // not a half-configured build — `SyncViewModel` decides each field on its own.
            resValue("string", "default_account_email", betaDefaultAccountEmail)
            // And the password, which is the line to stop at before copying this block anywhere
            // else. It is a credential compiled into an artefact, readable with `aapt2 dump
            // resources`, and it is here on one condition — that `mue.beta.password` holds a
            // throwaway for a disposable account on a server nobody off this network can reach.
            // `betaDefaultAccountPassword`'s note at the top of the file carries the argument in
            // full and says what stops being true if that condition ever does. This line belongs
            // to `beta` and to no other build type: `verifyReleaseCarriesNoBetaDefaults` checks
            // that on the release APK rather than trusting the absence of a line here.
            resValue("string", "default_account_password", betaDefaultAccountPassword)
        }

        /*
         * The build the owner actually carries: `release`'s speed with `debug`'s trust store.
         *
         * A debug build is not what the app feels like. Measured over 24 tab switches on one
         * device: debug spends 13–29 frames past 64 ms with a worst case of 81–200 ms, while the
         * same source under R8 spends 0–1 and never passes 100 ms. The half-second before a tab
         * moves is unoptimised, un-precompiled code, not the layout.
         *
         * But `release` cannot reach his server: the network security config that trusts a
         * user-installed authority lives in the `debug` source set on purpose, so a shipped build
         * only ever trusts the platform store. Judging the speed then costs the synchronisation,
         * and judging the synchronisation costs the speed.
         *
         * `local` initialises from `release` — minified, shrunk, R8'd, baseline profile and all —
         * and adds only the debug source set's `res/xml` and manifest overlay. Production is
         * untouched: `release` is still built from `main` + `release` alone, and this variant is
         * signed with the debug key, so it can never be published.
         */
        create("local") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            /*
             * No suffix, and now the line says why instead of merely being null.
             *
             * `local` *is* the daily application. The data directory under
             * `fr.kristenjestin.mue` is the one holding his weight history, so this variant has to
             * answer to that identity exactly and installing it over a release build has to be an
             * update rather than a second icon. It inherits from `release`, which carries no
             * suffix, so the assignment changes nothing today — it is here so that a suffix added
             * to `release` later cannot reach the one variant that must never move.
             */
            applicationIdSuffix = null
            /*
             * Which leaves the version name as the only place the two can differ, so it is used:
             * sharing an `applicationId` means Android's app info screen shows one entry, and
             * `1.0-local` against `1.0` is what tells him which of the two is currently installed.
             */
            versionNameSuffix = "-local"
            signingConfig = signingConfigs.getByName("debug")
            /*
             * The build the owner carries, and therefore the one this whole flag exists for. It
             * already reads `src/debug`'s network security config; this is the application-level
             * half of the same decision, and without it `local` would refuse at the keyboard an
             * address the platform underneath it would have allowed on the wire.
             *
             * Like `beta`, stated and not inherited: `initWith(release)` brings "no" over.
             */
            resValue("bool", "cleartext_server_permitted", "true")
        }

    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        shaders = false
        renderScript = false
        /*
         * On by necessity, not by habit. AGP 9 defaults it off and fails configuration with
         * "defaultConfig contains custom resource values, but the feature is disabled" rather than
         * quietly dropping them, so the two `resValue` declarations in `defaultConfig` — the
         * launcher label and the launcher background — need it stated. It generates one
         * `values.xml` per variant and nothing else; `buildConfig` above stays off, the two
         * features being unrelated despite the similar spelling.
         */
        resValues = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // `MigrationTestHelper` reads the exported schemas off the test APK, so the folder KSP
    // writes them to has to ship as an androidTest asset.
    sourceSets.getByName("androidTest") {
        assets.srcDirs(files("$projectDir/schemas"))
    }

    /*
     * `local` and `beta` read the `debug` source set rather than owning a copy of it.
     *
     * The only thing either needs from there is the manifest overlay contributing
     * `android:networkSecurityConfig` and the `res/xml` it points at — the two files that let a
     * build trust a user-installed authority, and therefore reach a server whose certificate no
     * public CA would ever issue. Copying them would be four files free to drift, and the drift
     * would show up as a phone that pairs on one build and refuses on the other.
     *
     * Pointed, not copied, so the three builds that talk to his server cannot disagree about what
     * they trust. It is also why the per-variant launcher colour is a `resValue` in `defaultConfig`
     * and not a `values/colors.xml` under `src/debug`: anything added to that folder is added to
     * these two at the same time, and `local` is the one build that has to look like production.
     */
    listOf("local", "beta").forEach { trustsTheDevelopmentAuthority ->
        sourceSets.getByName(trustsTheDevelopmentAuthority) {
            manifest.srcFile("src/debug/AndroidManifest.xml")
            res.srcDirs(files("src/debug/res"))
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

/*
 * The six build types nobody wrote, pinned to the sandbox with the four above.
 *
 * `androidx.baselineprofile` does not add build types to a list — it derives a pair from every
 * non-debuggable build type it finds, so `release`, `local` and `beta` yield `nonMinifiedRelease` /
 * `benchmarkRelease`, `nonMinifiedLocal` / `benchmarkLocal` and `nonMinifiedBeta` /
 * `benchmarkBeta`. Each is created with `initWith` on its parent and the plugin sets no
 * `applicationIdSuffix` of its own — checked by disassembling
 * `benchmark-baseline-profile-gradle-plugin-1.5.0-rc02.jar`, in which the string does not occur.
 * Four of the six therefore inherited `fr.kristenjestin.mue` exactly, and every one of them is a
 * *macrobenchmark target*: an APK `:benchmark` installs over whatever already answers to that name
 * and then drives through UiAutomator.
 *
 * That is the same hole the suffixes above close, one plugin along, and leaving it open would have
 * left the guarantee resting on a habit again: `BaselineProfileGenerator`'s KDoc is careful to pin
 * `adb -s <serial>`, and a guarantee that depends on someone typing the right serial is not one.
 * `connectedNonMinifiedReleaseAndroidTest` needs no serial at all.
 *
 * `.debug` rather than an identity of their own, because these builds *are* instrumented tests and
 * the table above already names where those go. They overwrite each other on a device, which is
 * correct: only one is ever being measured and all of them are throwaway.
 *
 * ## Why `finalizeDsl` and not `buildTypes.configureEach`
 *
 * Because the obvious version silently does nothing, and the APK is what said so. Gradle's
 * `create(name, action)` fires the container's `configureEach` rules when the object is *added*
 * and runs the creation action afterwards, so a suffix set from `configureEach` is applied first
 * and then wiped by the plugin's own `initWith(release)`. Written that way,
 * `aapt dump badging app-nonMinifiedRelease.apk` still read `package: name='fr.kristenjestin.mue'`.
 * `finalizeDsl` runs once every plugin has finished contributing to the DSL and before any variant
 * is created, which is the only window where all ten build types exist and none is locked.
 *
 * This costs the profile nothing. What `BaselineProfileRule` records is class and method
 * descriptors, and those come from `namespace` — still `fr.kristenjestin.mue` — and not from
 * `applicationId`; the recording is unchanged and remains valid for the `release` APK it is
 * packaged into. What it does cost is `MUE_PACKAGE` in `:benchmark`, which names the target to
 * drive and had to follow.
 */
androidComponents.finalizeDsl { extension ->
    extension.buildTypes.configureEach {
        if (name.startsWith("nonMinified") || name.startsWith("benchmark")) {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-$name"
            // And they have to *look* like the sandbox too. Left alone, `nonMinifiedRelease`
            // installs under `.debug` still labelled `Mue`, which is the exact confusion the
            // three names exist to prevent — a launcher entry that reads as the daily app.
            resValue("string", "app_name", "Mue Debug")
            resValue("color", "launcher_background", "#1E7A47")
        }
    }
}

/*
 * The three `beta` defaults, checked on the release APK because that is where the claim is made.
 *
 * Everything else about these keys is decided in this file: `defaultConfig` declares the empty
 * string, one build type overrides it, and nobody intends to change that. Intent is exactly what
 * this task refuses to accept as the evidence. `default_account_password` carries a credential in
 * `beta`, and the sentence that makes that acceptable — "the application the owner carries, and
 * the artefact a store would get, contain none of it" — is a statement about a binary. A line in a
 * build script is not a binary, and the history of this very file says so twice over: the
 * `applicationIdSuffix` set from `buildTypes.configureEach` compiled, configured, ran and did
 * nothing, and it took `aapt dump badging` on the APK to notice. A resource that leaked into
 * `release` through a `matchingFallbacks`, an `initWith`, a merged source set or a plugin nobody
 * read would fail exactly as quietly.
 *
 * So the release APK is opened and its own resource table is read back:
 *
 *   1. each of the three resources must be absent from it or hold a blank string;
 *   2. no string in it, under any resource name at all, may equal a value `local.properties`
 *      configured — which is the half that still holds if some future line copies one of these
 *      across under a different name.
 *
 * The second rule compares whole pooled values rather than searching for a substring, on purpose:
 * a scan for a short password inside a 30 MB artefact finds it in a class name and fails a build
 * that was fine, and a check that cries wolf gets deleted by the third person it stops.
 *
 * `aapt2 dump resources` and not an unzip: `resources.arsc` is a binary table whose strings are
 * pooled and length-prefixed, and `strings(1)` over the APK answers a slightly different question
 * than the one asked. `aapt2` is the tool that produced the table, it ships with the build tools
 * this module already requires, and it is the command the arbitration was verified with by hand.
 */
val betaOnlyStringResources = listOf(
    "default_server_address",
    "default_account_email",
    "default_account_password",
)

abstract class VerifyNoBetaDefaultsInApk : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val builtArtifacts: Property<BuiltArtifactsLoader>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val aapt2: RegularFileProperty

    /** The resources that must be blank in this artefact, whatever any build type says. */
    @get:Input
    abstract val resourceNames: ListProperty<String>

    /**
     * What `local.properties` actually configured, so the check has something to look for even
     * when the resources themselves are fine. Blank entries are dropped by the registration: an
     * unconfigured machine has nothing to leak, and "" would match every empty string in the table.
     */
    @get:Input
    abstract val forbiddenValues: ListProperty<String>

    @get:OutputFile
    abstract val receipt: RegularFileProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun verify() {
        val apk = builtArtifacts.get().load(apkDirectory.get())?.elements?.singleOrNull()?.outputFile
            ?.let(::File)
            ?: error("no single APK to read; this task is wired to a variant that produced none")

        val dump = ByteArrayOutputStream()
        exec.exec {
            commandLine(aapt2.get().asFile.absolutePath, "dump", "resources", apk)
            standardOutput = dump
        }
        val lines = dump.toString(Charsets.UTF_8.name()).lines().map(String::trim)

        // `resource 0x7f0e003b string/default_account_password` followed by `() "…"`.
        val declaration = Regex("""^resource 0x[0-9a-fA-F]+ string/(\S+)$""")
        val value = Regex("""^\([^)]*\)\s+"(.*)"$""")

        val offences = mutableListOf<String>()
        val checked = mutableListOf<String>()

        lines.forEachIndexed { index, line ->
            val name = declaration.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            if (name !in resourceNames.get()) return@forEachIndexed
            val held = lines.drop(index + 1).firstOrNull(String::isNotEmpty)
                ?.let { value.find(it)?.groupValues?.get(1) }
                .orEmpty()
            checked += "string/$name = \"$held\""
            if (held.isNotBlank()) {
                offences += "string/$name carries \"$held\" and must be blank in this build"
            }
        }

        val pooled = lines.mapNotNull { value.find(it)?.groupValues?.get(1) }.toSet()
        forbiddenValues.get().filter { it in pooled }.forEach {
            offences += "the value \"$it\" configured in local.properties is in this resource table"
        }

        receipt.get().asFile.apply { parentFile.mkdirs() }
            .writeText((checked + offences).joinToString(System.lineSeparator(), postfix = System.lineSeparator()))

        if (offences.isNotEmpty()) {
            error(
                offences.joinToString(
                    separator = System.lineSeparator(),
                    prefix = "${apk.name} carries a `beta` default:" + System.lineSeparator(),
                    postfix = System.lineSeparator() +
                        "These three resources belong to `beta` alone, and one of them is a " +
                        "credential. See the note on `betaDefaultAccountPassword` in " +
                        "app/build.gradle.kts before changing either this task or the build type " +
                        "that produced this artefact.",
                ),
            )
        }
    }
}

/*
 * Where aapt2 is, without guessing harder than necessary.
 *
 * `android.buildToolsVersion` is the version AGP resolved for this build, so its folder is the one
 * whose `aapt2` produced the table being read back. The fallback exists because a machine may have
 * the SDK laid out with a newer build-tools only, and a verification that disappears when a tool
 * moves is worse than one that reads a neighbouring copy: the format of `dump resources` has not
 * changed across these versions. Absent entirely, the task fails and says so — `release` is not
 * assembled without proof.
 */
val resolvedBuildToolsVersion: String = android.buildToolsVersion

val aapt2Executable: Provider<File> = androidComponents.sdkComponents.sdkDirectory.map { sdk ->
    val executable = if (System.getProperty("os.name").startsWith("Windows")) "aapt2.exe" else "aapt2"
    val buildTools = sdk.asFile.resolve("build-tools")
    val preferred = buildTools.resolve(resolvedBuildToolsVersion).resolve(executable)
    when {
        preferred.isFile -> preferred
        else -> buildTools.listFiles().orEmpty()
            .sortedByDescending { it.name }
            .map { it.resolve(executable) }
            .firstOrNull { it.isFile }
            ?: preferred
    }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val verify = tasks.register<VerifyNoBetaDefaultsInApk>("verifyReleaseCarriesNoBetaDefaults") {
        group = "verification"
        description = "Reads the release APK's resource table back and fails if a `beta` default is in it."
        apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
        builtArtifacts.set(variant.artifacts.getBuiltArtifactsLoader())
        aapt2.fileProvider(aapt2Executable)
        resourceNames.set(betaOnlyStringResources)
        // Read here, at configuration time, from the same three vals the `beta` build type uses —
        // so the task looks for what this machine is actually configured with rather than for a
        // list someone remembered to update.
        forbiddenValues.set(
            listOf(betaDefaultServerAddress, betaDefaultAccountEmail, betaDefaultAccountPassword)
                .filter(String::isNotBlank),
        )
        receipt.set(layout.buildDirectory.file("reports/betaDefaults/release.txt"))
    }
    // Wired to `assembleRelease` rather than left to be remembered: the artefact this proves
    // something about is the one that task produces, and a check nobody runs proves nothing.
    // `matching` because the assemble tasks do not exist yet while variants are being created.
    tasks.matching { it.name == "assemble${variant.name.replaceFirstChar(Char::titlecase)}" }
        .configureEach { dependsOn(verify) }
}

kotlin {
    jvmToolchain(17)
}

/*
 * How the recorded profile gets into the APK.
 *
 * `saveInSrc` keeps the result under version control at
 * `app/src/release/generated/baselineProfiles/`, which is the difference between a profile and a
 * hope: a checked-in file is one every build packages and one a reviewer can read, where a
 * regenerated-on-demand file is whatever the last machine to run the emulator happened to record.
 *
 * `automaticGenerationDuringBuild` stays **off**, and it is the load-bearing line in this block.
 * Turned on, every `assembleRelease` would install the app and run an instrumentation on every
 * attached device — and the device attached to this machine beside the emulator is the owner's
 * own phone, over wireless debugging. Regeneration is a deliberate act, run against a named
 * serial, and `:benchmark`'s `BaselineProfileGenerator` says how.
 */
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    /*
     * The profile is a *rule*, so R8 keeps and lays out the classes it names even when it can see
     * no other reference to them. Off, R8 is free to move a startup class away from the classes
     * it is loaded beside, and the profile then names methods in pages the loader has to seek to.
     */
    dexLayoutOptimization = true
}

// Room stores its generated schemas here so migrations can be verified in tests.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Arch components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // `ProcessLifecycleOwner`: whether *the application* is in the foreground, rather than
    // whether some activity is. The live channel of sync PRD 9.4 is scoped to it, and the 700 ms
    // debounce it already carries is what keeps a rotation from closing and reopening a socket.
    implementation(libs.androidx.lifecycle.process)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // No navigation library: the shell is four sibling tabs with no back stack to model, so
    // `MueNavigationHost` is a saved integer, and the one tab that does have a stack --
    // Activity -- models it as a saved list of routes. Both are Compose's own AnimatedContent.

    // Storage
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    /*
     * What actually installs the baseline profile on the phone.
     *
     * The profile `:benchmark` records is packaged as `assets/dexopt/baseline.prof`, and on a
     * device that got the APK from Play the Play installer hands it to ART by itself. On every
     * other route — a sideload, an `adb install`, an update pushed to the owner's own phone —
     * nothing does, and the profile sits in the APK doing nothing at all. `ProfileInstaller` is
     * the library that writes it into ART's own store on first run, through the `androidx.startup`
     * initialiser it declares in its own manifest, so there is no call site for it here.
     *
     * It drags in `androidx.startup:startup-runtime` and nothing else; neither touches
     * `kotlinx-serialization`, so the `force` block below is unchanged by it. Checked with
     * `dependencies --configuration releaseRuntimeClasspath`, not assumed.
     */
    implementation(libs.androidx.profileinstaller)

    /*
     * The producer of that profile. This is not a code dependency in either direction: `:app`
     * does not compile against `:benchmark`, and the only artefact that crosses is the text file
     * of class and method names the `androidx.baselineprofile` plugin copies into
     * `src/release/generated/baselineProfiles/`.
     */
    baselineProfile(project(":benchmark"))

    // An activity draft is a nested structure, so `SavedStateHandle` holds it as one JSON
    // string rather than as a flat set of Bundle keys. A sync payload is stored the same way.
    implementation(libs.kotlinx.serialization.json)

    // Server synchronisation (sync PRD 19). The client, its engine and the deferred worker are
    // declared together so one Ktor version answers for all of them; see the note on the force
    // block below for why that version is not simply the newest.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.androidx.work.runtime.ktx)

    /*
     * FR-FOOD-003's scanner: the camera, and the decoder that reads a barcode out of its frames.
     *
     * PRD_FOOD 9.2 fixes both halves of this choice. "Le décodage est **local** … aucune image ne
     * quitte le téléphone" is why the bundled `com.google.mlkit:barcode-scanning` is used rather
     * than `play-services-mlkit-barcode-scanning`: the unbundled variant fetches its model through
     * Google Play services on first use, which is a network round trip about a photograph, on the
     * one path this PRD promises never leaves the device. The bundled artefact ships the model in
     * the APK, so a phone in flight mode decodes exactly as well as one on wifi.
     *
     * CameraX rather than `android.hardware.camera2`, for the reason every release note gives:
     * the lifecycle binding, the rotation handling and the analysis back-pressure are the parts
     * that are wrong on some device somewhere, and they are not parts worth re-deriving for one
     * screen. `camera-lifecycle` is what stops the preview when the sheet closes.
     *
     * **`camera-compose` and not `camera-view`**, and the difference is two whole libraries.
     * `camera-view` exists to give a `PreviewView` to a `View` hierarchy, and it declares
     * `androidx.appcompat` and `androidx.camera:camera-video` to do it — an `AppCompatActivity`
     * theme stack and a video recorder, in an app that has neither a `View` layout nor a
     * `Recorder` anywhere in it. `camera-compose` declares Compose, `camera-core` and
     * `camera-viewfinder`, and `CameraXViewfinder` is a composable that takes the `SurfaceRequest`
     * `Preview` already emits.
     *
     * Nothing added here drags a `kotlinx-serialization` artefact in: the `force` block below
     * still resolves 1.8.1 for all three, checked with
     * `dependencies --configuration debugRuntimeClasspath` after the change, not assumed.
     *
     * What ML Kit *does* bring is named in `AndroidManifest.xml` beside the permission, because
     * one of its transitive dependencies uploads usage telemetry unless it is switched off, and
     * PRD_FOOD 22's "seul le numéro est transmis" is a claim about the whole application.
     */
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.mlkit.barcode.scanning)

    // Local unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)

    // Ktor's own in-memory engine, so the sync client's path, bearer and error mapping are
    // asserted on the JVM without a socket. Test-only, on the same version as the client above,
    // so it adds nothing to the APK and nothing to the `force` block's reasoning below.
    testImplementation(libs.ktor.client.mock)

    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

/*
 * Realigns kotlinx-serialization on the version Room is built against.
 *
 * `androidx.room:room-migration` — what `MigrationTestHelper` parses the exported schema with —
 * asks for 1.8.1, while `androidx.savedstate` brings the 1.7.3 BOM, which pins the whole family
 * *strictly*. Two strict versions cannot be reconciled by ordering, so `force` is the only way
 * out; left alone, the helper dies inside its own generated serializers with an
 * `AbstractMethodError` on `GeneratedSerializer.typeParametersSerializers`.
 *
 * The force covers the app as well as the test APK on purpose: an instrumentation APK loads
 * shared classes from the app's classloader first, so pinning the test side alone would change
 * nothing. The app now serializes its activity draft with the very same version it declares
 * above, so the force only moves a library every party here already expects to share.
 *
 * Ktor is the third party to this pin, and it is why the client is on 3.2.4 rather than on the
 * newest release: 3.2.4 is the last version whose `ktor-serialization-kotlinx` asks for exactly
 * 1.8.1, so nothing here has to be re-derived. From 3.3.0 onward Ktor asks for 1.9.0, which
 * would force the pin up and put `MigrationTestHelper` back on a version of the runtime it is
 * not built against. Bumping Ktor past 3.2.x therefore means re-deriving this force and
 * re-running the instrumented suite, not editing one number.
 *
 * `kotlinx-serialization-json-io` is listed for completeness: Ktor pulls it as a third artefact
 * of the same family, and a pin covering two of three holds only until something moves the
 * third. It resolves to the same version today, so the line is a no-op, which is the point of
 * writing it now rather than after a skew has produced an `AbstractMethodError` nobody can place.
 */
configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlinx:kotlinx-serialization-core:${libs.versions.kotlinxSerialization.get()}",
        "org.jetbrains.kotlinx:kotlinx-serialization-json:${libs.versions.kotlinxSerialization.get()}",
        "org.jetbrains.kotlinx:kotlinx-serialization-json-io:${libs.versions.kotlinxSerialization.get()}",
    )
}
