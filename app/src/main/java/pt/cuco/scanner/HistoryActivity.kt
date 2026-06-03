package pt.cuco.scanner

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import pt.cuco.scanner.databinding.ActivityHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val entries: MutableList<HistoryEntry> = mutableListOf()
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        entries.addAll(HistoryRepository.loadAll(this))
        adapter = HistoryAdapter(this, entries)
        binding.listHistory.adapter = adapter
        updateEmptyState()

        binding.btnBack.setOnClickListener { finish() }

        binding.listHistory.setOnItemClickListener { _, _, position, _ ->
            val entry = entries[position]
            val intent = Intent(this, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_SERIAL, entry.serial)
                putExtra(WebViewActivity.EXTRA_CTIME, entry.ctime)
                putExtra(WebViewActivity.EXTRA_USAGE, entry.usage)
            }
            startActivity(intent)
            finish()
        }

        binding.listHistory.setOnItemLongClickListener { _, _, position, _ ->
            val entry = entries[position]
            AlertDialog.Builder(this)
                .setTitle(R.string.history_delete_confirm_title)
                .setMessage(R.string.history_delete_confirm_message)
                .setPositiveButton(R.string.history_delete_confirm_ok) { _, _ ->
                    HistoryRepository.deleteEntry(this, entry.id)
                    entries.removeAt(position)
                    adapter.notifyDataSetChanged()
                    updateEmptyState()
                }
                .setNegativeButton(R.string.history_delete_confirm_cancel, null)
                .show()
            true
        }
    }

    private fun updateEmptyState() {
        binding.tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private class HistoryAdapter(
        context: Context,
        private val items: List<HistoryEntry>,
    ) : ArrayAdapter<HistoryEntry>(context, 0, items) {

        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_history, parent, false)

            val entry = items[position]

            val tvSerial = view.findViewById<TextView>(R.id.tv_serial)
            val tvCtime = view.findViewById<TextView>(R.id.tv_ctime)
            val tvUsage = view.findViewById<TextView>(R.id.tv_usage)
            val tvDate = view.findViewById<TextView>(R.id.tv_date)
            val tvStatus = view.findViewById<TextView>(R.id.tv_status)

            tvSerial.text = if (entry.serial.length > 16) {
                entry.serial.take(16) + "…"
            } else {
                entry.serial
            }
            tvCtime.text = context.getString(R.string.history_field_ctime, entry.ctime)
            tvUsage.text = context.getString(R.string.history_field_usage, entry.usage)
            tvDate.text = dateFormat.format(Date(entry.timestamp))

            val submitted = entry.status == HistoryEntry.STATUS_SUBMITTED
            tvStatus.text = context.getString(
                if (submitted) R.string.status_submitted else R.string.status_pending
            )
            val bgColor = if (submitted) Color.parseColor("#E6F4EA") else Color.parseColor("#F1F3F4")
            val textColor = if (submitted) Color.parseColor("#137333") else Color.parseColor("#5F6368")
            tvStatus.setTextColor(textColor)
            tvStatus.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bgColor)
                cornerRadius = 12f * context.resources.displayMetrics.density
            }

            return view
        }
    }
}
