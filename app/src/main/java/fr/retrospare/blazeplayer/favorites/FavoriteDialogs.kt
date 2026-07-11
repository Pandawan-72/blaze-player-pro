package fr.retrospare.blazeplayer.favorites

import fr.retrospare.blazeplayer.ui.showPremium
import android.content.Context
import android.widget.Toast

object FavoriteDialogs {

    private data class FavoriteListEntry(
        val category: FavoriteCategory,
        val folder: FavoriteFolder
    )

    /** Modal de confirmation pour ajouter le dossier courant aux favoris. */
    fun showAddFavoriteDialog(context: Context, category: FavoriteCategory, folder: FavoriteFolder) {
        val alreadyFavorite = FavoritesManager.isFavorite(context, category, folder.path, folder.shareId)
        if (alreadyFavorite) {
            android.app.AlertDialog.Builder(context)
                .setTitle(context.getString(fr.retrospare.blazeplayer.R.string.dialog_title_already_favorite))
                .setMessage(context.getString(fr.retrospare.blazeplayer.R.string.dialog_already_favorite_message, folder.name))
                .setPositiveButton(context.getString(fr.retrospare.blazeplayer.R.string.action_ok), null)
                .showPremium()
            return
        }
        android.app.AlertDialog.Builder(context)
            .setTitle(context.getString(fr.retrospare.blazeplayer.R.string.dialog_add_favorite_folder))
            .setMessage(context.getString(fr.retrospare.blazeplayer.R.string.dialog_add_favorite_message, folder.name))
            .setPositiveButton(context.getString(fr.retrospare.blazeplayer.R.string.add)) { _, _ ->
                FavoritesManager.addFavorite(context, category, folder)
                Toast.makeText(context, context.getString(fr.retrospare.blazeplayer.R.string.toast_added_to_favorites_short), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(context.getString(fr.retrospare.blazeplayer.R.string.action_cancel), null)
            .showPremium()
    }

    /** Liste des dossiers favoris, avec un vrai style de ligne cliquable (icône dossier, chevron)
     *  plutôt que la liste texte générique d'AlertDialog qui donnait l'impression de texte statique. */
    fun showFavoritesList(
        context: Context,
        category: FavoriteCategory,
        onOpenFavorite: (FavoriteFolder) -> Unit
    ) {
        showFavoritesList(context, listOf(category)) { _, favorite -> onOpenFavorite(favorite) }
    }

    /** Variante multi-catégories utilisée par Blaze Video : depuis que l'onglet vidéo est unifié,
     *  le même bouton "Favoris" doit afficher les dossiers locaux et les dossiers réseau. Les
     *  favoris restent stockés dans leurs catégories d'origine pour ne pas casser les anciens
     *  réglages ni l'écran de gestion. */
    fun showFavoritesList(
        context: Context,
        categories: List<FavoriteCategory>,
        onOpenFavorite: (FavoriteCategory, FavoriteFolder) -> Unit
    ) {
        val entries = categories.distinct().flatMap { category ->
            FavoritesManager.getFavorites(context, category).map { FavoriteListEntry(category, it) }
        }
        if (entries.isEmpty()) {
            android.app.AlertDialog.Builder(context)
                .setTitle(context.getString(fr.retrospare.blazeplayer.R.string.favorites))
                .setMessage(context.getString(fr.retrospare.blazeplayer.R.string.dialog_no_favorite_folders))
                .setPositiveButton(context.getString(fr.retrospare.blazeplayer.R.string.action_ok), null)
                .showPremium()
            return
        }

        val view = android.view.LayoutInflater.from(context).inflate(fr.retrospare.blazeplayer.R.layout.dialog_favorites_list, null, false)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(fr.retrospare.blazeplayer.R.id.recyclerFavorites)
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)

        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(context.getString(fr.retrospare.blazeplayer.R.string.dialog_favorite_folders))
            .setView(view)
            .setNegativeButton(context.getString(fr.retrospare.blazeplayer.R.string.action_manage), null)
            .setPositiveButton(context.getString(fr.retrospare.blazeplayer.R.string.action_close), null)
            .create()

        recycler.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = entries.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val v = android.view.LayoutInflater.from(parent.context)
                    .inflate(fr.retrospare.blazeplayer.R.layout.item_favorite_folder, parent, false)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val entry = entries[position]
                val f = entry.folder
                holder.itemView.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.tvFavoriteName)?.text = f.name
                val tvSubtitle = holder.itemView.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.tvFavoriteSubtitle)
                val subtitle = when {
                    !f.shareName.isNullOrEmpty() -> f.shareName
                    entry.category == FavoriteCategory.NETWORK -> context.getString(fr.retrospare.blazeplayer.R.string.tab_network)
                    entry.category == FavoriteCategory.LOCAL -> context.getString(fr.retrospare.blazeplayer.R.string.tab_blaze_video)
                    else -> null
                }
                if (!subtitle.isNullOrEmpty()) {
                    tvSubtitle?.text = subtitle
                    tvSubtitle?.visibility = android.view.View.VISIBLE
                } else {
                    tvSubtitle?.visibility = android.view.View.GONE
                }
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    onOpenFavorite(entry.category, f)
                }
            }
        }

        dialog.show()
        fr.retrospare.blazeplayer.ui.DialogButtonStyler.style(dialog)
        // "Gérer" ouvre un second dialogue de suppression, plutôt que de complexifier celui-ci
        // avec des boutons par ligne.
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            dialog.dismiss()
            showManageFavorites(context, entries)
        }
    }

    private fun showManageFavorites(context: Context, entries: List<FavoriteListEntry>) {
        if (entries.isEmpty()) return
        val labels = entries.map { entry ->
            val f = entry.folder
            when {
                !f.shareName.isNullOrEmpty() -> "${f.shareName} — ${f.name}"
                entry.category == FavoriteCategory.NETWORK -> "${context.getString(fr.retrospare.blazeplayer.R.string.tab_network)} — ${f.name}"
                else -> f.name
            }
        }.toTypedArray()
        val checked = BooleanArray(entries.size)
        android.app.AlertDialog.Builder(context)
            .setTitle(context.getString(fr.retrospare.blazeplayer.R.string.dialog_remove_favorites))
            .setMultiChoiceItems(labels, checked) { _, i, isChecked -> checked[i] = isChecked }
            .setPositiveButton(context.getString(fr.retrospare.blazeplayer.R.string.action_remove)) { _, _ ->
                entries.forEachIndexed { i, entry ->
                    val f = entry.folder
                    if (checked[i]) FavoritesManager.removeFavorite(context, entry.category, f.path, f.shareId)
                }
            }
            .setNegativeButton(context.getString(fr.retrospare.blazeplayer.R.string.action_cancel), null)
            .showPremium()
    }
}
