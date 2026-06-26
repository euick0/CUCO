package pt.cuco.scanner

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import pt.cuco.scanner.databinding.ActivitySettingsBinding

/**
 * Lets the user edit the CUCo URL pieces (client / lang / base URL) so the app
 * keeps working when Inforlandia changes the link, and import them from a pasted
 * full URL. See [SettingsRepository].
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadIntoFields()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnImport.setOnClickListener { importFromUrl() }
        binding.btnSave.setOnClickListener { saveAndFinish() }
        binding.btnReset.setOnClickListener {
            SettingsRepository.resetToDefaults(this)
            loadIntoFields()
            Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadIntoFields() {
        binding.etClient.setText(SettingsRepository.client(this))
        binding.etLang.setText(SettingsRepository.lang(this))
        binding.etBaseUrl.setText(SettingsRepository.baseUrl(this))
    }

    private fun importFromUrl() {
        val raw = binding.etImportUrl.text?.toString().orEmpty()
        val parsed = SettingsRepository.parseCucoUrl(raw)
        if (parsed == null) {
            Toast.makeText(this, R.string.settings_import_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        parsed.client?.let { binding.etClient.setText(it) }
        parsed.lang?.let { binding.etLang.setText(it) }
        binding.etBaseUrl.setText(parsed.baseUrl)

        val machineId = parsed.machineId
        if (machineId != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_import_open_title)
                .setMessage(getString(R.string.settings_import_open_message, machineId))
                .setPositiveButton(R.string.settings_import_open_ok) { _, _ ->
                    persistFields()
                    startActivity(
                        Intent(this, WebViewActivity::class.java)
                            .putExtra(WebViewActivity.EXTRA_SERIAL, machineId)
                    )
                    finish()
                }
                .setNegativeButton(R.string.history_delete_confirm_cancel, null)
                .show()
        } else {
            Toast.makeText(this, R.string.settings_import_done, Toast.LENGTH_SHORT).show()
        }
    }

    private fun persistFields() {
        SettingsRepository.save(
            this,
            baseUrl = binding.etBaseUrl.text?.toString().orEmpty(),
            client = binding.etClient.text?.toString().orEmpty(),
            lang = binding.etLang.text?.toString().orEmpty(),
        )
    }

    private fun saveAndFinish() {
        persistFields()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
