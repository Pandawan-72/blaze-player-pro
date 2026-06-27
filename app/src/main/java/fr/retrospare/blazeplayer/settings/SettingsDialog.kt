package fr.retrospare.blazeplayer.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import fr.retrospare.blazeplayer.R

object SettingsDialog {

    fun showChoice(
        context: Context,
        title: String,
        choices: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        val dialog = BottomSheetDialog(context, R.style.ThemeOverlay_BlazePlayer_BottomSheet)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_settings_choice, null)

        view.findViewById<TextView>(R.id.tvDialogTitle).text = title

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerChoices)
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = ChoiceAdapter(choices, selectedIndex) { index ->
            onSelected(index)
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }
}

class ChoiceAdapter(
    private val choices: List<String>,
    private var selectedIndex: Int,
    private val onSelected: (Int) -> Unit
) : RecyclerView.Adapter<ChoiceAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_setting_choice, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.tvChoice.text = choices[position]
        holder.ivCheck.visibility = if (position == selectedIndex) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onSelected(position) }
    }

    override fun getItemCount() = choices.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvChoice: TextView = view.findViewById(R.id.tvChoice)
        val ivCheck: ImageView = view.findViewById(R.id.ivCheck)
    }
}
