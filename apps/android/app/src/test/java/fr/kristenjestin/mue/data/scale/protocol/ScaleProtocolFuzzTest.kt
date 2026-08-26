package fr.kristenjestin.mue.data.scale.protocol

import fr.kristenjestin.mue.domain.model.ScaleFrameEvent
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Fuzzing léger et **déterministe** du décodage (FR-SCALE-031).
 *
 * Une trame arrive d'un appareil tiers dont on ne contrôle ni le micrologiciel, ni l'état des
 * piles, ni la façon dont la pile BLE d'Android découpe ses notifications. La seule discipline
 * tenable est qu'un octet inattendu produise un événement, jamais une exception remontant dans un
 * callback où plus personne ne peut la rattraper : ces tests le vérifient sur toutes les
 * troncatures et toutes les mutations d'un octet de la trame réelle de PRD_SCALE 14.4.
 *
 * Déterministe et non aléatoire : la graine est figée. Un test de protocole qui échoue une fois
 * sur cent est un test qu'on finit par ignorer.
 */
class ScaleProtocolFuzzTest {

    private val realFrame = hexToBytes(REAL_STABLE_WEIGHT_FRAME)

    /**
     * Une notification tronquée est le cas le plus banal — MTU, reconnexion, fin de portée. Aucune
     * ne doit lever, et aucune ne doit produire de mesure : la trame réelle n'a aucun préfixe qui
     * se terminerait par la fin `0xAA`.
     */
    @Test
    fun `toutes les troncatures de la trame de poids réelle sont rejetées sans exception`() {
        for (length in 0 until realFrame.size) {
            val truncated = realFrame.copyOf(length)

            val event = Hb9027Session().onFrame(truncated)

            assertIs<ScaleFrameEvent.Rejected>(
                event,
                "troncature à $length octets : ${truncated.toHex()}",
            )
        }
    }

    /**
     * Le contrôle par OU exclusif couvre la longueur, le produit, le type et les données ; les
     * deux octets restants sont le contrôle lui-même et la fin. **Aucune mutation d'un seul octet
     * ne peut donc passer**, y compris celle de l'octet de produit — la variante `0x26` de
     * BR-SCALE-004 n'est acceptable que parce que son contrôle est recalculé.
     */
    @Test
    fun `toute mutation d'un octet de la trame réelle est rejetée sans exception`() {
        for (index in realFrame.indices) {
            for (mask in intArrayOf(0x01, 0x10, 0x80, 0xFF)) {
                val mutated = realFrame.copyOf()
                mutated[index] = (mutated[index].toInt() xor mask).toByte()

                val event = Hb9027Session().onFrame(mutated)

                assertIs<ScaleFrameEvent.Rejected>(
                    event,
                    "mutation ${HbFrames.hex(mask)} en position $index : ${mutated.toHex()}",
                )
            }
        }
    }

    /**
     * Des octets quelconques, de toutes les tailles plausibles pour une notification BLE.
     *
     * Deux garanties, et **aucune des deux n'est le décompte de la boucle** : le décodage ne lève
     * pas — s'il levait, la boucle ne finirait pas —, et surtout du bruit ne devient jamais une
     * mesure (BR-SCALE-003). C'est la seconde qui vaut d'être écrite : `Rejected` et `Ignored` sont
     * les deux seules issues acceptables, et lister les quatre variantes de `ScaleFrameEvent`
     * n'aurait affirmé que leur existence.
     *
     * Sa force reste modeste, et il faut le dire : une suite d'octets tirée au hasard n'atteint
     * presque jamais le contrôle par OU exclusif, faute de porter l'en-tête et la longueur d'une
     * trame. Ce sont les deux balayages ci-dessus — troncatures et mutations d'un octet, exhaustifs
     * sur une trame **réelle** — qui éprouvent réellement la validation.
     */
    @Test
    fun `des octets aléatoires déterministes ne lèvent jamais ni ne produisent de mesure`() {
        val random = Random(seed = 20260826)
        val measured = mutableListOf<String>()

        repeat(2_000) {
            val bytes = random.nextBytes(random.nextInt(0, 24))
            val session = Hb9027Session()

            when (session.onFrame(bytes)) {
                is ScaleFrameEvent.Rejected, ScaleFrameEvent.Ignored -> Unit
                is ScaleFrameEvent.Weight, is ScaleFrameEvent.Impedance -> measured += bytes.toHex()
            }
        }

        assertTrue(measured.isEmpty(), "du bruit est devenu une mesure : $measured")
    }

    /**
     * La même garantie pour le pilote fictif : lui aussi reçoit des octets qu'il n'a pas choisis.
     *
     * Le pilote fictif parle un protocole étranger à la famille HB, si bien que la trame réelle
     * tronquée est pour lui du bruit comme un autre — et une trame HB **entière** aussi, ce que la
     * dernière assertion vérifie : deux pilotes livrés ensemble ne doivent pas se répondre l'un pour
     * l'autre (FR-SCALE-030).
     */
    @Test
    fun `le pilote fictif ne lève et ne mesure sur aucune entrée qui ne soit la sienne`() {
        val random = Random(seed = 20260827)
        val session = FakeScaleDriver.newSession()
        val measured = mutableListOf<String>()

        fun feed(bytes: ByteArray) {
            when (session.onFrame(bytes)) {
                is ScaleFrameEvent.Rejected, ScaleFrameEvent.Ignored -> Unit
                is ScaleFrameEvent.Weight, is ScaleFrameEvent.Impedance -> measured += bytes.toHex()
            }
        }

        repeat(1_000) { feed(random.nextBytes(random.nextInt(0, 12))) }
        for (length in 0..realFrame.size) feed(realFrame.copyOf(length))

        assertTrue(measured.isEmpty(), "le pilote fictif a mesuré ce qui ne le concerne pas : $measured")
    }

    /**
     * Les commandes sortantes ne sont jamais interprétées comme des trames entrantes : un écho de
     * notre propre écriture, que certaines piles BLE restituent, ne doit pas devenir une mesure.
     */
    @Test
    fun `un écho d'une commande sortante est rejeté`() {
        val session = Hb9027Session()

        for (write in session.onSubscribed()) {
            val event = session.onFrame(write.bytes)

            assertIs<ScaleFrameEvent.Rejected>(event, write.bytes.toHex())
        }
    }
}
