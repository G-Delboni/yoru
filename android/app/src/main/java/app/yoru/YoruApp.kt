// Application: objeto único que vive enquanto o app estiver aberto.
// Aqui ficam o banco, as preferências e o motor de sincronia, criados uma vez só.
// (Em projetos maiores isso vira injeção de dependência com Hilt; aqui, simplicidade.)
package app.yoru

import android.app.Application
import android.os.Build
import app.yoru.data.Settings
import app.yoru.data.YoruDatabase
import app.yoru.sync.PairingListener
import app.yoru.sync.SyncClient
import app.yoru.sync.SyncEngine

class YoruApp : Application() {
    // `by lazy`: só é criado no primeiro uso, e depois reaproveitado.
    val db: YoruDatabase by lazy { YoruDatabase.open(this) }
    val settings: Settings by lazy { Settings(this) }
    val pairing: PairingListener by lazy { PairingListener(this) }
    val client: SyncClient by lazy { SyncClient() }
    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    val sync: SyncEngine by lazy {
        SyncEngine(db, settings, client, deviceName = deviceName)
    }
}
