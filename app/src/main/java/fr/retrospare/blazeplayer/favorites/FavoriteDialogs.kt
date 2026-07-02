package fr.retrospare.blazeplayer.favorites

import android.content.Context
import android.widget.Toast

object FavoriteDialogs {

    /** Modal de confirmation pour ajouter le dossier courant aux favoris. */
    fun showAddFavoriteDialog(context: Context, category: FavoriteCategory, folder: FavoriteFolder) {
        val alreadyFavorite = FavoritesManager.isFavorite(context, category, folder.path, folder.shareId)
        if (alreadyFavorite) {
            android.app.AlertDialog.Builder(context)
                .setTitle("Déjà en favoris")
                .setMessage("« ${folder.name} » est déjà dans tes dossiers favoris.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("Ajouter dossier favori")
            .setMessage("Ajouter « ${folder.name} » à tes dossiers favoris ? Tu pourras y accéder directement depuis l'accueil.")
            .setPositiveButton("Ajouter") { _, _ ->
                FavoritesManager.addFavorite(context, category, folder)
                Toast.makeText(context, "Ajouté aux favoris", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Liste des dossiers favoris, avec un vrai style de ligne cliquable (icône dossier, chevron)
     *  plutôt que la liste texte générique d'AlertDialog qui donnait l'impression de texte statique. */
    fun showFavoritesList(
        context: Context,
        category: FavoriteCategory,
        onOpenFavorite: (FavoriteFolder) -> Unit
    ) {
        val favorites = FavoritesManager.getFavorites(context, category)
        if (favorites.isEmpty()) {
            android.app.AlertDialog.Builder(context)
                .setTitle("Favoris")
                .setMessage("Aucun dossier favori pour l'instant.\n\nDans le navigateur, ouvre le menu (⋮) à côté d'un dossier pour l'ajouter à tes favoris.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val view = android.view.LayoutInflater.from(context).inflate(fr.retrospare.blazeplayer.R.layout.dialog_favorites_list, null, false)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(fr.retrospare.blazeplayer.R.id.recyclerFavorites)
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)

        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("Dossiers favoris")
            .setView(view)
            .setNegativeButton("Gérer", null)
            .setPositiveButton("Fermer", null)
            .create()

        recycler.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = favorites.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val v = android.view.LayoutInflater.from(parent.context)
                    .inflate(fr.retrospare.blazeplayer.R.layout.item_favorite_folder, parent, false)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val f = favorites[position]
                holder.itemView.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.tvFavoriteName)?.text = f.name
                val tvSubtitle = holder.itemView.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.tvFavoriteSubtitle)
                if (category == FavoriteCategory.NETWORK && !f.shareName.isNullOrEmpty()) {
                    tvSubtitle?.text = f.shareName
                    tvSubtitle?.visibility = android.view.View.VISIBLE
                } else {
                    tvSubtitle?.visibility = android.view.View.GONE
                }
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    onOpenFavorite(f)
                }
            }
        }

        dialog.show()
        // "Gérer" ouvre un second dialogue de suppression, plutôt que de complexifier celui-ci
        // avec des boutons par ligne.
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            dialog.dismiss()
            showManageFavorites(context, category)
        }
    }

    private fun showManageFavorites(context: Context, category: FavoriteCategory) {
        val favorites = FavoritesManager.getFavorites(context, category)
        if (favorites.isEmpty()) return
        val labels = favorites.map { f ->
            if (category == FavoriteCategory.NETWORK && !f.shareName.isNullOrEmpty()) "${f.shareName} — ${f.name}" else f.name
        }.toTypedArray()
        val checked = BooleanArray(favorites.size)
        android.app.AlertDialog.Builder(context)
            .setTitle("Retirer des favoris")
            .setMultiChoiceItems(labels, checked) { _, i, isChecked -> checked[i] = isChecked }
            .setPositiveButton("Retirer") { _, _ ->
                favorites.forEachIndexed { i, f ->
                    if (checked[i]) FavoritesManager.removeFavorite(context, category, f.path, f.shareId)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
