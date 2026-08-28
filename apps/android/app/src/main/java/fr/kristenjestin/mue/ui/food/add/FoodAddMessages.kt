package fr.kristenjestin.mue.ui.food.add

import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.repository.LookupFailure
import java.util.Locale

/**
 * Every word the `Add food` sheet and the food picker put on screen (PRD_FOOD 7, 11, 15 and 18).
 *
 * Constants rather than resources, exactly as `FoodDayMessages` and `LogActivityMessages` are:
 * Mue ships in one language, and a string a test can name is a string a test cannot mistype.
 * The accessibility labels sit here too — PRD_FOOD 18 makes them part of the interface.
 *
 * **No validation message lives here.** PRD_FOOD 15's refusals are `FoodValidation`'s own
 * sentences, asserted character for character by its unit tests; restating one here would let the
 * screen and the rule drift apart. What this file adds is only what the domain has no opinion
 * about: the name of a control, the shape of an announcement, and the two words a save button
 * can carry.
 */
internal object FoodAddMessages {

    // region the sheet itself (PRD_FOOD 7)

    /**
     * What the header says on the ways in — and **only** there.
     *
     * The sheet used to carry this one word over all five of its stages, so the scan panel and
     * the quick-add form both announced themselves as `Add food`: "quand je rentre dans « scan a
     * barcode », j'ai le « add food »". A header exists to say which screen this is, and a header
     * that says the same thing on five screens has stopped doing that. Every other stage now
     * names itself through [stageTitle], reusing the very words on the card that led there so the
     * title is the tap the reader just made, read back.
     */
    const val ADD_TITLE: String = "Add food"

    /** FR-FOOD-008 reuses this very sheet to correct a line that already exists. */
    const val EDIT_TITLE: String = "Edit entry"

    /**
     * The single way out, named as what it does.
     *
     * It was `Close` on a cross, beside a second control lower down that went back one stage —
     * two exits, two meanings, and no way to tell from the glyph which one dropped the work.
     * There is one now, it is an arrow, and it steps back exactly as far as there is to go.
     */
    const val BACK: String = "Back"

    // endregion

    // region the ways in (PRD_FOOD 7, FR-FOOD-002 to 005)

    /**
     * Not "What did you eat?".
     *
     * A moment later today has not happened yet, and the sheet opens on it just the same: the `+`
     * of tonight's dinner is pressed at six o'clock as readily as at nine. The past tense told
     * that reader they were in the wrong place. This asks about the entry being written, which is
     * true whether the food is already eaten or is about to be — and it stays a question about the
     * food rather than becoming a vague one about "an item".
     */
    const val PATHS_EYEBROW: String = "What are you adding?"
    const val PATHS_TITLE: String = "Pick a way in."

    const val SEARCH_PATH: String = "Search a food"
    const val SEARCH_PATH_DESCRIPTION: String = "The catalogue, your products, your own foods"

    const val RECIPE_PATH: String = "Use a recipe"
    const val RECIPE_PATH_DESCRIPTION: String = "One of your saved preparations"

    const val SCAN_PATH: String = "Scan a barcode"

    /**
     * The description says **both** ways in, and that is PRD_FOOD 18 rather than a flourish.
     *
     * The section calls the typed number "une alternative complète à la caméra", and a card that
     * advertised only the camera would read, to somebody who has refused it or has no camera, as
     * a path that is closed. It is not: everything past the number is identical.
     */
    const val SCAN_PATH_DESCRIPTION: String = "Read a packaged product, or type its number"

    const val QUICK_PATH: String = "Quick add"
    const val QUICK_PATH_DESCRIPTION: String = "A name and an energy, when that is all you know"

    /**
     * The header of each stage, which is the card that led to it.
     *
     * [SCAN_PATH] and [QUICK_PATH] are reused verbatim rather than reworded: the reader tapped
     * `Scan a barcode`, so `Scan a barcode` is what the next screen should be called, and a
     * second wording for the same place is a second thing to learn. The two stages that are not
     * reached from a card of their own take the question their section already asks —
     * [AMOUNT_SECTION] and [SERVINGS_SECTION] — which is the same principle one level down.
     *
     * [FROZEN] keeps [EDIT_TITLE]: it is only ever reached by correcting a stored line, and
     * there is no card behind it to name.
     */
    fun stageTitle(stage: FoodAddStage): String = when (stage) {
        FoodAddStage.PATHS -> ADD_TITLE
        FoodAddStage.SCAN -> SCAN_PATH
        FoodAddStage.QUICK -> QUICK_PATH
        FoodAddStage.AMOUNT -> AMOUNT_SECTION
        FoodAddStage.SERVINGS -> SERVINGS_SECTION
        FoodAddStage.FROZEN -> EDIT_TITLE
    }

    /**
     * The same header when the sheet is posing a proposal (PRD_FOOD 12).
     *
     * Only the first stage is renamed, and deliberately: a planning sheet has exactly two stages
     * — choose the recipe, then say how many servings and which moment — and the second asks the
     * identical question the journal's does, so giving it a second name would be two words for
     * one screen. [stageTitle] answers everything else, so a stage that cannot be reached while
     * planning still has a title rather than a blank header.
     */
    fun planStageTitle(stage: FoodAddStage): String = when (stage) {
        FoodAddStage.PATHS -> PLAN_TITLE
        else -> stageTitle(stage)
    }

    // endregion

    // region planning (PRD_FOOD 12 and FR-PLAN-001)

    /**
     * What the sheet is called on a day that has not happened yet.
     *
     * Not `Add food`, which would promise a journal line PRD_FOOD 22 forbids on such a day, and
     * not a coach's word either — PRD_FOOD 12 is explicit that there is no vocabulary of
     * suggestion-as-advice here. A meal is being planned; that is all it says.
     */
    const val PLAN_TITLE: String = "Plan a meal"

    const val PLAN_EYEBROW: String = "What are you planning?"

    /**
     * PRD_FOOD 8.5: "Une proposition référence toujours une recette ; un aliment simple se
     * journalise directement et n'est pas planifié."
     *
     * So the four ways in become one, and the sentence says why rather than leaving three cards
     * quietly missing. Naming the rule is what stops it reading as an unfinished screen.
     */
    const val PLAN_PATHS_TITLE: String = "A suggestion is always a recipe."

    const val PLAN_ONLY_RECIPES: String =
        "A single food goes straight into the journal, on the day itself."

    const val PLAN_RECIPE_PATH_DESCRIPTION: String =
        "One of your saved preparations, for a moment of this day"

    /** The action at the foot of a planning sheet, and what it writes. */
    const val PLAN_MEAL: String = "Plan this meal"

    const val PLAN_FAILED: String = "Couldn't plan it. Nothing was changed."

    /**
     * PRD_FOOD 17, on the one refusal that has no field to sit beside: the recipe went while the
     * sheet was open.
     *
     * `RecipeRepository.delete` frees the proposals that referenced a recipe, and it can run under
     * an open planning sheet. Without this the primary button would refuse in silence.
     */
    const val PLAN_RECIPE_GONE: String =
        "That recipe is no longer available. Choose another one."

    /**
     * FR-PLAN-001: "Un moment déjà pourvu demande confirmation avant de remplacer sa proposition."
     *
     * The moment is named in the title, because the answer depends on *which* one — somebody who
     * meant Thursday's lunch and is being asked about Thursday's dinner has learned something
     * from the question.
     */
    fun replacePlanTitle(slot: MealSlot): String = "${slot.label} already has a suggestion"

    const val REPLACE_PLAN_BODY: String =
        "A moment carries one suggestion at a time. This one takes its place; " +
            "the journal and both recipes stay as they are."

    const val REPLACE_PLAN_CONFIRM: String = "Replace it"

    const val CANCEL: String = "Cancel"

    /**
     * A moment's hours, and the fact that something is already proposed in it (PRD_FOOD 8.5).
     *
     * The hours stay: a moment is defined by them, and a planning panel that dropped them would
     * be the only place in the module naming a moment without saying what it is.
     */
    fun slotAlreadyPlanned(hours: String): String = "$hours · Already suggested"

    // endregion

    // region the food, and how much of it (FR-FOOD-006)

    const val CHANGE_FOOD: String = "Choose another food"

    /** FR-FOOD-004: the same gesture on the recipe card, worded the same way. */
    const val CHANGE_RECIPE: String = "Choose another recipe"
    const val AMOUNT_SECTION: String = "How much?"
    const val PORTIONS_LABEL: String = "Usual portions"
    const val FEWER_PORTIONS: String = "One portion fewer"
    const val MORE_PORTIONS: String = "One portion more"

    /** PRD_FOOD 8.6: the counter is an aid to typing, and the exact weight always wins. */
    const val PORTIONS_HINT: String = "Typing a weight takes over from the counter"

    const val WEIGHT_LABEL: String = "Weight"
    const val VOLUME_LABEL: String = "Volume"

    /** FR-FOOD-006: only a food carrying a cooking ratio is ever asked this. */
    const val COOKED_STATE_LABEL: String = "Weighed"

    const val PER_100_SECTION: String = "Per 100"
    const val CONTRIBUTION_SECTION: String = "In this entry"

    const val ENERGY_NOUN: String = "Energy"
    const val PROTEIN_NOUN: String = "Protein"
    const val CARBS_NOUN: String = "Carbohydrate"
    const val FAT_NOUN: String = "Fat"
    const val FIBRE_NOUN: String = "Fibre"

    // endregion

    // region the scan (FR-FOOD-003, PRD_FOOD 9.2, 17 and 18)

    const val SCAN_SECTION: String = "Scan a barcode"

    /** What the viewfinder is, for a screen reader that cannot be shown a viewfinder. */
    const val SCANNER_DESCRIPTION: String =
        "Camera viewfinder. Point it at a barcode, or type the number below."

    /**
     * The field's label, and the sentence under it.
     *
     * "Or type it" and not "if the camera fails": PRD_FOOD 18 puts the two on the same footing,
     * and a label that framed one as the other's repair would tell a person using a screen
     * reader, or a phone with no camera, that they are on the degraded path. They are not — the
     * lookup, the copy and the prefilled creation are byte-identical either way.
     */
    const val BARCODE_LABEL: String = "Barcode"
    const val BARCODE_PLACEHOLDER: String = "3017620422003"
    const val BARCODE_HINT: String = "Point the camera at it, or type the number under the bars."

    /** The hint on a device that has no camera at all: no mention of a camera it cannot use. */
    const val BARCODE_HINT_TYPED_ONLY: String = "Type the number printed under the bars."

    const val LOOK_UP: String = "Look it up"
    const val LOOKING_UP: String = "Looking it up…"

    /** PRD_FOOD 9.2: the product is copied into the local catalogue at the moment of adding. */
    const val ADD_THIS_PRODUCT: String = "Add this product"

    /**
     * The same button when the barcode is already in the catalogue.
     *
     * A different word because it is a different act: nothing is copied, nothing is downloaded,
     * and the row being chosen is the one the person may have corrected by hand since. PRD_FOOD
     * 9.2's "une modification ultérieure de la fiche distante ne change rien" is exactly what
     * makes re-scanning a kept product a *local* lookup, and the label says so.
     */
    const val USE_THIS_FOOD: String = "Use this food"

    const val SCANNED_PRODUCT_SECTION: String = "Found"

    /** PRD_FOOD 9.2 and 17: an incomplete card is nominal, and its gaps stay gaps. */
    const val INCOMPLETE_CARD: String =
        "Open Food Facts does not document every value for this product. " +
            "The missing ones stay empty and can be filled in after adding it."

    const val PRODUCT_NOT_FOUND: String = "Open Food Facts has no card for this barcode"

    /** PRD_FOOD 17: "Bascule vers la création manuelle pré-remplie." */
    const val CREATE_FROM_BARCODE: String = "Create it from the label"

    const val TRY_LOOKUP_AGAIN: String = "Try again"

    /** PRD_FOOD 17: a refused camera is explained, and the rest of the path is untouched. */
    const val CAMERA_REFUSED: String =
        "Mue cannot use the camera. Type the barcode below instead — it does exactly the same " +
            "thing. You can allow the camera again in Android settings."

    const val CAMERA_NOT_YET_ALLOWED: String =
        "Mue can read the barcode for you if you allow the camera. Typing it works just as well."

    const val ALLOW_CAMERA: String = "Allow the camera"

    /**
     * The way back after a refusal, and **not** a second prompt.
     *
     * FR-TIMER-012 already paid for this lesson on the notification permission: Android does not
     * show the dialog again once it has been refused, so a control still labelled "Allow" would
     * be a button that visibly does nothing. This one names where the answer can actually change.
     */
    const val OPEN_CAMERA_SETTINGS: String = "Open Android settings"

    /** No camera on the device: nothing to grant, so nothing about a permission is said. */
    const val CAMERA_ABSENT: String = "This device has no camera. Type the barcode below."

    /** The promise the module already makes on a failed write: nothing was lost. */
    const val COPY_FAILED: String = "Couldn't add the product. Nothing was changed."

    // endregion

    // region the quick add (FR-FOOD-005)

    const val QUICK_SECTION: String = "Quick add"

    /**
     * Present tense, because the meal may not have happened.
     *
     * "What was it?" is a fine question about lunch three hours ago and a wrong one about the
     * restaurant dinner being written down at five in the afternoon. The present answers both,
     * and asks for exactly the same thing.
     */
    const val QUICK_NAME_LABEL: String = "What is it?"
    const val QUICK_ENERGY_LABEL: String = "Energy"
    const val QUICK_PROTEIN_LABEL: String = "Protein · optional"

    /** PRD_FOOD 8.4: a quick add is stored approximate, and says so before it is saved. */
    const val QUICK_APPROXIMATE: String = "Saved as an estimate"

    // endregion

    // region logging and correcting a recipe line (FR-FOOD-004 and 008)

    const val SERVINGS_SECTION: String = "How many servings?"

    /** PRD_FOOD 13.1: what one serving of the chosen recipe is worth, before any count is typed. */
    const val PER_SERVING_SECTION: String = "Per serving"

    /**
     * PRD_FOOD 8.3: "les quantités des ingrédients sont exprimées pour la recette entière".
     *
     * So the number of servings the recipe was written for is what gives the field below its
     * unit: two servings of a recipe that serves four is half of it.
     */
    fun serves(baseServings: Int): String = "Serves $baseServings"

    /** `25 min`, and nothing at all when a recipe states no preparation time. */
    fun prepTime(minutes: Int): String = "$minutes min"

    /** FR-FOOD-004: a new line is computed from the recipe as it stands right now. */
    const val SERVINGS_FROM_RECIPE: String = "Computed from this recipe's ingredients"

    /**
     * The noun alone: the section above it already asks "how many", and the participle carried an
     * assumption the field does not need. A portion and a half is a portion and a half whether it
     * is already gone or is about to be served.
     */
    const val SERVINGS_LABEL: String = "Servings"

    /**
     * The two ends of the servings stepper.
     *
     * They say "a quarter" because that is the step PRD_FOOD 15 sets and `Servings` owns — but
     * neither string is what *applies* it: `FoodAddDraft.steppedServings` asks the domain to move
     * the value and to say whether it may. These name the button; they do not define the rule.
     */
    const val FEWER_SERVINGS: String = "A quarter serving fewer"
    const val MORE_SERVINGS: String = "A quarter serving more"

    /** PRD_FOOD 8.4: a recipe edited since is not a line rewritten. */
    const val SERVINGS_FROZEN: String = "Rescaled from what this entry was saved with"

    // endregion

    // region a line whose food is gone (PRD_FOOD 17)

    const val MISSING_FOOD: String = "This food is no longer in the catalogue"
    const val MISSING_FOOD_DETAIL: String =
        "Its values stay as they were saved. The moment and the time can still be changed."

    // endregion

    // region when and where (PRD_FOOD 10.3, FR-FOOD-007)

    const val SLOT_SECTION: String = "When?"

    /**
     * The label on the one quiet line the moment gets, and what opening it says.
     *
     * The sheet no longer **asks** for a moment. The owner's words are the whole design — *"le
     * système détecte le type en fonction de l'heure mais je dois pouvoir l'override si je le
     * souhaite, mais par défaut c'est caché et auto"* — so the hour is the only thing entered and
     * the moment is what the hour already decided. A grid of tiles beside the clock was asking for
     * the same fact twice, and with six moments it was asking for it across three rows.
     *
     * It is still **findable and still overridable**, because PRD_FOOD 10.3 forbids the windows
     * from constraining anything: a midday meal eaten at 11:30 or at 15:00 is the ordinary case,
     * not the edge one, and taking the choice away would forbid it.
     */
    const val SLOT_LABEL: String = "Moment"
    const val CHANGE_SLOT: String = "Change"
    const val SLOT_SHEET_TITLE: String = "Which moment?"
    const val CLOSE_SLOT_SHEET: String = "Close the moment picker"

    /** What the quiet line reads when nothing has overruled the clock: `Lunch · 12:00 – 14:30`. */
    fun slotWithHours(label: String, hours: String): String = "$label · $hours"

    /** PRD_FOOD 18: the line alone says `Lunch`; a reader needs to hear what tapping it does. */
    fun changeSlotDescription(label: String): String = "Moment: $label. Change it."

    const val TIME_LABEL: String = "Time"
    const val CHANGE_TIME: String = "Change"
    const val TIME_SHEET_TITLE: String = "What time?"
    const val CLOSE_TIME_SHEET: String = "Close the time picker"
    const val USE_THIS_TIME: String = "Use this time"

    // endregion

    // region saving (FR-FOOD-008)

    const val SAVE_ENTRY: String = "Save entry"
    const val SAVE_CHANGES: String = "Save changes"
    const val DELETE_ENTRY: String = "Delete entry"
    const val ENTRY_DELETED: String = "Entry deleted"

    /** The promise the two shipped modules already make on a failed write: nothing was lost. */
    const val SAVE_FAILED: String = "Couldn't save. Your entry is still here."
    const val DELETE_FAILED: String = "Couldn't delete. Your entry is still here."
    const val TRY_AGAIN: String = "Try again"

    /**
     * PRD_FOOD 15: a quantity is required before a line can be written at all.
     *
     * `Quantity` is the domain's own noun — `FoodValidation.INGREDIENT_QUANTITY_ERROR` uses it —
     * so the two refusals a person can meet on this field speak the same word. "how much you had"
     * did not, and told a reader writing down tonight's dinner that they had already eaten it.
     */
    const val NO_QUANTITY: String = "Enter a quantity"

    // endregion

    // region the picker (PRD_FOOD 9.4 and 11)

    const val PICKER_TITLE: String = "Choose a food"
    const val SEARCH_PLACEHOLDER: String = "Rice, skyr, olive oil…"
    const val SEARCH_LABEL: String = "Search foods"
    const val CLEAR_SEARCH: String = "Clear the search"
    const val RECENT_SECTION: String = "Recently used"

    /**
     * What sits under the recently used when nothing has been typed.
     *
     * **Not** the `Foods` view's own word for the same rows, which is `Catalogue`, and that is
     * deliberate: on *this* screen `Catalogue` is already the name of the source chip that
     * restricts the list to the Ciqual subset. The same word as a heading over the unrestricted
     * list would read as a filter that had been applied — two controls, one noun, opposite
     * meanings. `Foods` calls its provenance chip `Generic` and has no such collision.
     */
    const val CATALOGUE_SECTION: String = "All foods"

    const val RESULTS_SECTION: String = "Results"
    const val SOURCE_FILTER_LABEL: String = "Where a food came from"
    const val SOURCE_ALL: String = "All"
    const val SOURCE_CIQUAL: String = "Catalogue"
    const val SOURCE_OPEN_FOOD_FACTS: String = "Packaged"
    const val SOURCE_CUSTOM: String = "Mine"

    /** PRD_FOOD 17: an empty search says what is true and offers the way out of it. */
    const val NOTHING_RECENT: String = "Nothing logged yet. Search the catalogue above."
    const val NO_RESULTS: String = "No food matches that."
    const val CREATE_FOOD: String = "Create a food"

    // endregion

    // region the recipe picker (FR-FOOD-004)

    /**
     * `Choose a recipe`, said the way `Choose a food` is said.
     *
     * The two pickers are one gesture with two catalogues behind it, and wording them differently
     * would suggest they behave differently. Neither leaves the sheet: both come back to it.
     */
    const val RECIPE_PICKER_TITLE: String = "Choose a recipe"
    const val RECIPE_SEARCH_PLACEHOLDER: String = "Sheet-pan salmon, porridge…"
    const val RECIPE_SEARCH_LABEL: String = "Search your recipes"
    const val RECIPE_RESULTS_SECTION: String = "Your recipes"

    /** PRD_FOOD 17: "aucune recette enregistrée" — an invitation, and no fake recipe. */
    const val NO_RECIPES: String = "No recipes yet. A recipe is a saved shortcut: these foods, in these quantities."
    const val NO_RECIPE_MATCHES: String = "No recipe matches that."
    const val CREATE_RECIPE: String = "Create a recipe"

    // endregion

    /**
     * The two words a food uses for its states, folded the way [FoodLabels.cookedSuffix] folds
     * the cooked one.
     *
     * [FoodLabels] owns the cooked half and nothing else; the raw half is the same fold through
     * [Locale.ROOT] — never the phone's locale, for the reason `Food.fold` gives: a Turkish
     * device lower-cases `I` to a dotless one, and a label must not change shape with the region.
     */
    fun stateWord(food: Food, cooked: Boolean): String =
        if (cooked) FoodLabels.cookedSuffix(food) else food.rawLabel.trim().lowercase(Locale.ROOT)

    /**
     * What the quantity field is called, **with the state the number is read in beside it**.
     *
     * This is the whole of a finding the planning pass paid for: 600 g of *cooked* rice typed
     * against a raw reference counts nearly three times the energy actually eaten, and a field
     * labelled only `Weight` says nothing about which of the two the person is holding. A food
     * that declares no cooked state carries no such ambiguity and keeps the plain noun.
     */
    fun quantityLabel(food: Food, cooked: Boolean): String {
        val noun = when (food.referenceUnit) {
            ReferenceUnit.GRAM -> WEIGHT_LABEL
            ReferenceUnit.MILLILITRE -> VOLUME_LABEL
        }
        if (!food.hasCookedState) return noun
        return "$noun, ${stateWord(food, cooked)}"
    }

    /** `Per 100 g raw` — what the figures under it are quoted against (PRD_FOOD 8.2). */
    fun per100Label(food: Food): String {
        val basis = "$PER_100_SECTION ${food.referenceUnit.symbol}"
        return if (food.hasCookedState) "$basis ${stateWord(food, cooked = false)}" else basis
    }

    /**
     * PRD_FOOD 13.1's first line said out loud: the weight the values are actually computed from.
     *
     * Shown only when a cooked weight was typed, because that is the only time the number on the
     * scale and the number behind the figures are two different numbers.
     */
    fun countedAs(referenceWeight: String, rawWord: String): String =
        "Counted as $referenceWeight $rawWord"

    /**
     * The hours a moment usually covers, under its name (PRD_FOOD 10.3's table).
     *
     * The two controls used to sit side by side with nothing linking them, so `Breakfast` at 18:00
     * was reachable and read as a contradiction — "je peux sélectionner breakfast, mais avoir un
     * time à 18h, je comprends pas". Printing each moment's own window is the half of the answer
     * that arrives *before* the mistake: a reader who can see that breakfast means five to ten has
     * been told what a moment is, and the pairing stops being a guess.
     *
     * The bounds are [fr.kristenjestin.mue.domain.logic.MealSlotRules]' own and are never restated
     * here; the en dash is all this function adds.
     */
    fun slotHours(from: String, untilExclusive: String): String = "$from – $untilExclusive"

    /**
     * The other half: what to say when the time and the moment disagree anyway.
     *
     * **Not a refusal.** PRD_FOOD 10.3 is explicit that the windows "ne créent aucune contrainte :
     * elles ne font que choisir la valeur par défaut", and a late breakfast is a real meal. Making
     * the moment follow the clock would let the clock overrule a person who has already said which
     * moment they meant; making the time follow the moment would throw away an hour they typed on
     * purpose. So the screen states the relation instead of enforcing it — it names the moment the
     * clock would have chosen, and then says the choice stands.
     */
    fun timeOutsideSlot(timeLabel: String, byTheClock: MealSlot, chosen: MealSlot): String =
        "$timeLabel usually falls in ${byTheClock.label}. Kept in ${chosen.label}, as you chose."

    /** What the save button announces it will do, moment and day included (PRD_FOOD 18). */
    fun saveDescription(label: String, slot: MealSlot, dateLabel: String): String =
        "$label, ${slot.label}, $dateLabel"

    /**
     * PRD_FOOD 17: "Réseau indisponible pendant un scan → **message explicite**."
     *
     * Four values, four sentences, and the `when` is exhaustive so a fifth failure could not be
     * added without a sentence being written for it. That exhaustiveness is the whole guarantee:
     * a `else ->` here is how four named causes become one shrug, and the four lead to four
     * genuinely different next moves — wait for a signal, try again in a moment, stop trying this
     * product today, or tell somebody the service is broken.
     *
     * None of them says "try again" on its own. That is the button's job, and PRD_FOOD 17 keeps
     * the other three ways of adding a line reachable throughout, which is what
     * [lookupFailureDetail] says out loud.
     */
    fun lookupFailure(reason: LookupFailure): String = when (reason) {
        LookupFailure.OFFLINE -> "No connection to Open Food Facts"
        LookupFailure.TIMEOUT -> "Open Food Facts did not answer in time"
        LookupFailure.SERVICE_ERROR -> "Open Food Facts could not answer"
        LookupFailure.MALFORMED_RESPONSE -> "Open Food Facts sent something Mue could not read"
    }

    /** What to do about it — one sentence per cause, for the same reason as above. */
    fun lookupFailureDetail(reason: LookupFailure): String = when (reason) {
        LookupFailure.OFFLINE ->
            "The barcode never left this phone. Try again when you are back online, " +
                "or create the food from its label."

        LookupFailure.TIMEOUT ->
            "The request was given twelve seconds. Try again, or create the food from its label."

        LookupFailure.SERVICE_ERROR ->
            "The service is having trouble, not your phone. " +
                "Try again in a moment, or create the food from its label."

        LookupFailure.MALFORMED_RESPONSE ->
            "This is not something you can fix. Create the food from its label; " +
                "the barcode is kept."
    }

    /**
     * PRD_FOOD 17: "Produit absent d'Open Food Facts → bascule vers la création manuelle
     * pré-remplie." The number is printed because it is what the creation will carry, and because
     * a barcode read by a camera is worth checking against the packet before anything is built
     * on it.
     */
    fun productNotFoundDetail(barcode: String): String =
        "Nothing is filed under $barcode. Create the food from its label — " +
            "Mue keeps the barcode, so scanning it again will find it."

    /** PRD_FOOD 9.4 and FR-CATALOG-004: a result row says where its food came from. */
    fun sourceLabel(source: FoodSource): String = when (source) {
        FoodSource.CIQUAL -> SOURCE_CIQUAL
        FoodSource.OPEN_FOOD_FACTS -> SOURCE_OPEN_FOOD_FACTS
        FoodSource.CUSTOM -> SOURCE_CUSTOM
    }
}
