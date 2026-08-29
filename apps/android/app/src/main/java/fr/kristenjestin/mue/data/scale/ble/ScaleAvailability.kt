package fr.kristenjestin.mue.data.scale.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import fr.kristenjestin.mue.domain.model.ScaleUnavailableReason

/**
 * Ce qu'Android exige avant qu'un scan BLE puisse rendre quoi que ce soit (PRD_SCALE 16.1, 18.5).
 *
 * **Il n'existe qu'une seule détection, et c'est celle-ci.** Elle a été écrite deux fois pendant
 * l'écriture du module — une fois dans `AndroidScaleTransport`, qui en a besoin pour refuser
 * d'ouvrir une session, une fois dans `ui/scale/ScalePermissions.kt`, qui en a besoin pour dire
 * laquelle des quatre phrases de PRD_SCALE 18.5 montrer. Deux copies d'un même diagnostic finissent
 * par diverger, et le symptôme serait le pire de tous : un écran qui explique une cause pendant que
 * la liaison en refuse une autre. La version qui fait autorité est donc celle qui n'a **aucune
 * dépendance Compose**, ici, dans la couche que l'interface a déjà le droit d'importer ; le sens
 * inverse n'existe nulle part dans ce dépôt.
 *
 * Trois conditions distinctes, dans l'ordre où l'interface les explique :
 *
 * 1. les permissions d'exécution de ce niveau d'API sont accordées ;
 * 2. la radio Bluetooth est allumée ;
 * 3. avant l'API 31 seulement, la localisation système est allumée — une exigence du scanner de la
 *    plateforme, pas de Mue, que PRD_SCALE 16.1 veut *expliquée* plutôt que subie sous la forme
 *    d'une liste vide.
 *
 * **Rien ici ne demande quoi que ce soit.** Lire cet objet est entièrement passif : c'est ce qui
 * rend vraie par construction la promesse de FR-SCALE-025 — la demande de permission part d'un
 * geste délibéré, au premier appairage, et de nulle part ailleurs. Le quatrième cas, le refus
 * définitif, ne se lit pas d'un `Context` : il demande le drapeau persisté et l'activité courante,
 * et reste donc l'affaire de `ScalePermissionsState`.
 *
 * BR-SCALE-011 gouverne le tout : aucune fonction de Mue ne dépend de ces réponses, seule la
 * balance en dépend.
 */
internal object ScaleAvailability {

    /**
     * Les permissions que ce niveau d'API exige réellement (PRD_SCALE 16.1).
     *
     * À partir d'Android 12, le couple `BLUETOOTH_SCAN` — déclaré `neverForLocation`, parce que Mue
     * ne lit un résultat de scan que pour reconnaître une balance — et `BLUETOOTH_CONNECT`.
     * Jusqu'à Android 11, aucune des deux n'existe : `BLUETOOTH` et `BLUETOOTH_ADMIN` sont
     * accordées à l'installation et la seule chose à demander à l'exécution est
     * `ACCESS_FINE_LOCATION`, que la plateforme exige de tout scan BLE sur ces versions.
     *
     * `minSdk` vaut 26, donc les deux branches sont livrées et les deux doivent être tenues.
     */
    // Les deux constantes d'Android 12 sont compilées comme des chaînes littérales et ne sont lues
    // que sur un appareil qui les connaît : c'est ce test de version qui choisit la liste.
    @SuppressLint("InlinedApi")
    val REQUIRED_PERMISSIONS: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /**
     * La localisation système est-elle une condition sur cet appareil ? (PRD_SCALE 16.1, 18.5)
     *
     * Vrai jusqu'à Android 11 seulement. Condition **distincte** de la permission de localisation :
     * les deux peuvent être accordées pendant que l'interrupteur général est éteint, et dans ce cas
     * le scanner ne rend rien du tout plutôt qu'une erreur.
     */
    val REQUIRES_SYSTEM_LOCATION: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    /** Toutes les permissions de [REQUIRED_PERMISSIONS] sont détenues. */
    fun hasPermissions(context: Context): Boolean =
        REQUIRED_PERMISSIONS.all { isGranted(context, it) }

    /** Une permission nommée, pour les rares appelants qui en isolent une (`BLUETOOTH_CONNECT`). */
    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * La radio est allumée.
     *
     * Un adaptateur nul signifie que l'appareil n'a pas de Bluetooth du tout — le manifeste déclare
     * la fonctionnalité facultative pour exactement ce cas — et se lit ici comme « éteinte » :
     * PRD_SCALE 18.5 propose de l'allumer, et BR-SCALE-011 laisse le reste de Mue intact de toute
     * façon.
     */
    fun isBluetoothEnabled(context: Context): Boolean =
        ContextCompat.getSystemService(context, BluetoothManager::class.java)
            ?.adapter
            ?.isEnabled == true

    /**
     * La localisation système est allumée, ce qui avant l'API 31 conditionne tout scan.
     *
     * Toujours `true` à partir d'Android 12, où l'exigence a disparu : un appelant peut lire cette
     * réponse sans condition et n'agir dessus que si [REQUIRES_SYSTEM_LOCATION].
     */
    fun isSystemLocationEnabled(context: Context): Boolean {
        if (!REQUIRES_SYSTEM_LOCATION) return true
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
            ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /**
     * Ce qui empêche de chercher, ou `null` (PRD_SCALE 18.5).
     *
     * L'ordre — permission, radio, localisation système — n'est pas arbitraire : c'est celui dans
     * lequel l'interface les explique, parce que la permission conditionne jusqu'à la façon dont
     * Mue peut proposer d'allumer la radio. Deux ordres différents pour un même diagnostic
     * produiraient deux messages différents pour une même situation, ce qui est précisément la
     * raison d'être de ce fichier.
     */
    fun reason(context: Context): ScaleUnavailableReason? = when {
        !hasPermissions(context) -> ScaleUnavailableReason.PERMISSION_MISSING
        !isBluetoothEnabled(context) -> ScaleUnavailableReason.BLUETOOTH_OFF
        !isSystemLocationEnabled(context) -> ScaleUnavailableReason.SYSTEM_LOCATION_OFF
        else -> null
    }
}
