package gh.knust.echowear

import android.app.Application
import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class EchoWearApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installBouncyCastle()
    }

    /**
     * Android ships a stripped-down BouncyCastle under the provider name "BC"
     * that has no post-quantum algorithms. Leaving it in place means ML-KEM
     * lookups silently resolve to the wrong provider, so the bundled full
     * version has to replace it at position 1 before any crypto runs.
     *
     * PqcSession uses the low-level BC API rather than JCA, so this is belt
     * and braces - but anything else in the app that asks the JCA for a
     * post-quantum primitive will now find it.
     */
    private fun installBouncyCastle() {
        runCatching {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            Log.i("EchoWear", "BouncyCastle ${BouncyCastleProvider().version} installed")
        }.onFailure {
            Log.e("EchoWear", "could not install BouncyCastle", it)
        }
    }
}
