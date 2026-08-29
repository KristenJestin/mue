package fr.kristenjestin.mue.data.scale.protocol

import fr.kristenjestin.mue.domain.model.ScaleAdvertisement
import fr.kristenjestin.mue.domain.model.ScaleDriver
import fr.kristenjestin.mue.domain.model.ScaleDriverRegistry

/**
 * Les pilotes livrés avec Mue (PRD_SCALE 9.2, 15).
 *
 * **C'est la seule liste partagée qu'un nouveau modèle de balance modifie** : ajouter un modèle,
 * c'est écrire un fichier de pilote et ajouter une ligne ici. Aucun écran, aucun état d'interface,
 * aucune table de la base, et aucun pilote existant (FR-SCALE-030).
 *
 * **Ce registre n'est pas une table de correspondance.** Il ne connaît aucun critère de
 * reconnaissance : il interroge ses pilotes dans l'ordre et rend le premier qui répond oui. Une
 * table centrale « ce nom annoncé → ce pilote » ne saurait exprimer que les critères qu'elle
 * aurait prévus, et le jour où un modèle ne se reconnaît qu'à un octet de ses données fabricant,
 * il faudrait en étendre le schéma pour tout le monde (PRD_SCALE 9.2).
 *
 * **L'ordre compte.** Le premier pilote qui reconnaît l'emporte : les pilotes spécifiques doivent
 * précéder les pilotes génériques d'une famille. Aujourd'hui la question ne se pose pas, un seul
 * pilote matériel existe.
 *
 * **Pourquoi le drapeau de débogage vient de l'extérieur.** Le module Android est compilé avec
 * `buildFeatures { buildConfig = false }` : la classe `BuildConfig` n'est tout simplement pas
 * générée, `BuildConfig.DEBUG` n'existe donc pas, et l'activer pour ce seul besoin imposerait de
 * modifier le fichier de build partagé. Le drapeau est donc **passé** au registre par le conteneur
 * d'injection, ce qui a de surcroît le mérite de garder ce paquet entièrement dépourvu de
 * dépendance Android, donc pilotable en JVM pure (PRD_SCALE 21.3). Côté conteneur :
 *
 * ```kotlin
 * val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
 * val drivers: ScaleDriverRegistry = MueScaleDrivers.forBuild(debuggable)
 * ```
 *
 * `MueScaleDrivers` utilisé tel quel — sans appeler [forBuild] — ne contient **que** les pilotes
 * matériels : le pilote fictif est inerte par construction, quelle que soit la variante de build.
 */
object MueScaleDrivers : ScaleDriverRegistry {

    /**
     * Les pilotes d'appareils réels. **La ligne à ajouter pour un nouveau modèle est ici.**
     */
    private val hardware = ScaleDriverList(
        listOf(
            Hb9027Driver,
        ),
    )

    /**
     * Les pilotes réels plus les pilotes fictifs, pour une variante déboguable. `by lazy` afin
     * qu'une version publiée, qui n'appelle jamais [forBuild] avec `true`, ne les instancie pas.
     */
    private val withFakes by lazy {
        ScaleDriverList(hardware.drivers + listOf(FakeScaleDriver))
    }

    override val drivers: List<ScaleDriver> get() = hardware.drivers

    override fun byId(id: String): ScaleDriver? = hardware.byId(id)

    override fun recognise(advertisement: ScaleAdvertisement): ScaleDriver? =
        hardware.recognise(advertisement)

    /**
     * Le registre à utiliser pour la variante de build courante.
     *
     * @param debuggable Vrai uniquement dans une application déboguable. Le pilote fictif
     *   [FakeScaleDriver] n'apparaît qu'à cette condition : il rend l'application pilotable sur
     *   émulateur, où le BLE réel est impossible, et n'a rien à faire dans une version publiée.
     */
    fun forBuild(debuggable: Boolean): ScaleDriverRegistry = if (debuggable) withFakes else hardware
}

/**
 * Un registre à partir d'une liste donnée.
 *
 * Extrait de [MueScaleDrivers] pour deux raisons : la logique de reconnaissance — « le premier qui
 * répond oui » — n'existe qu'une fois, et un test peut composer un registre arbitraire, notamment
 * pour éprouver FR-SCALE-030 avec un pilote qui ne fournit pas l'impédance, sans avoir à
 * l'enregistrer dans l'application.
 */
internal class ScaleDriverList(override val drivers: List<ScaleDriver>) : ScaleDriverRegistry {

    /**
     * `null` quand le pilote n'existe plus : une balance appairée par une version antérieure peut
     * référencer un pilote retiré depuis, et ce cas doit se lire, jamais planter (PRD_SCALE 9.2).
     */
    override fun byId(id: String): ScaleDriver? = drivers.firstOrNull { it.id == id }

    override fun recognise(advertisement: ScaleAdvertisement): ScaleDriver? =
        drivers.firstOrNull { it.recognises(advertisement) }
}
