package proto.media.fiezta.features.domain.phone.warnings

import android.Manifest
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import proto.media.fiezta.R

class WarningsPhoneActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var allSet: View
    private val resolver: AppWarningResolver = AppWarningResolver(this) { microphoneRequest.launch(Manifest.permission.RECORD_AUDIO) }
    private val microphoneRequest: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        resolver.onMicrophoneAnswered(granted)
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_warnings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = findViewById(R.id.warnings_list)
        allSet = findViewById(R.id.warnings_all_set)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val warnings = PhoneSetupProbe.read(this).warnings
        AppWarningRows.show(list, warnings, resolver::resolve)
        allSet.visibility = if (warnings.isEmpty()) View.VISIBLE else View.GONE
    }
}
