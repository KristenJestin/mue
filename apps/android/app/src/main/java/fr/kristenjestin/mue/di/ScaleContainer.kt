package fr.kristenjestin.mue.di

import android.content.Context
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.repository.RoomScaleRepository
import fr.kristenjestin.mue.domain.repository.ScaleRepository

/**
 * Tout ce dont le module balance a besoin, en un seul endroit.
 *
 * [AppContainer] y gagne **une** propriété pour tout le module, exactement comme le minuteur, la
 * synchronisation et le module alimentaire avant lui, afin que le registre de pilotes, la couche
 * de liaison Bluetooth et les écrans d'appairage encore à écrire puissent s'y brancher sans que le
 * conteneur livré ait à rebouger (PRD_SCALE 21.2, PRD 20.2).
 *
 * Il ne contient pour l'instant que la persistance. Le registre de pilotes et la couche BLE
 * arrivent ensuite ; ils prendront la même forme — une propriété `by lazy` de plus ici, aucune
 * ligne ailleurs — ce qui est exactement ce que FR-SCALE-030 exige du coût d'un nouveau modèle de
 * balance.
 *
 * Paresseux comme tout ce que [AppContainer] tient : le repository ouvre la base de données, et un
 * démarrage à froid qui n'atteint jamais l'écran des balances ne doit pas le payer.
 *
 * @property applicationContext Le contexte applicatif, que la couche Bluetooth de PRD_SCALE 21.2
 *   réclamera pour son `BluetoothManager` et ses vérifications de permission. Il est déjà là parce
 *   qu'un conteneur de module en prend un — c'est la forme des trois autres — et non parce que la
 *   persistance en aurait l'usage.
 */
class ScaleContainer(
    val applicationContext: Context,
    private val database: MueDatabase,
) {
    /**
     * Les balances appairées (PRD_SCALE 9.3).
     *
     * Sans `SyncOutbox`, contrairement à toutes les autres propriétés de repository des
     * conteneurs de ce dépôt : PRD_SCALE 22 tient cette collection hors du fil, et
     * `RoomScaleRepository` n'accepte donc pas d'outbox du tout.
     */
    val scaleRepository: ScaleRepository by lazy { RoomScaleRepository(database.scaleDao()) }
}
