# Blaze Player — Design Specs

> Référence de design à consulter avant de coder chaque écran.  
> Stack : Android / Kotlin / Material 3 / ViewBinding / minSdk 28

---

## 1. Palette de couleurs

| Token | Hex | Usage |
|---|---|---|
| `background` | `#0F1117` | Fond global de l'app |
| `background_auth` | `#0A0F0B` | Fond écrans Auth et Paywall |
| `surface_card` | `#181B26` | Fond des cards (médias, réglages, etc.) |
| `surface` | `#1A1D27` | Fond des sheets, dialogs |
| `surface_variant` | `#22263A` | Fond des chips, inputs inactifs |
| `green_accent` | `#3DD68C` | CTA principal, icône lecture active, progress |
| `blue_accent` | `#378ADD` | Liens, icône réseau, état actif onglets |
| `yellow_accent` | `#F0B429` | Badges "Essai", avertissements, étoiles |
| `on_background` | `#FFFFFF` | Texte primaire |
| `on_surface` | `#E8E8E8` | Texte secondaire |
| `on_surface_variant` | `#9B9DB3` | Texte tertiaire, placeholders, labels |
| `outline` | `#3A3D4F` | Bordures, séparateurs, dividers |
| `error` | `#FF5C5C` | Messages d'erreur |
| `green_container` | `#1B4D33` | Fond badge Pro, état actif doux |
| `on_green_container` | `#3DD68C` | Texte sur green_container |

### Règle d'application
- Chaque écran part du fond `background` (`#0F1117`).
- Les écrans Auth et Paywall utilisent `background_auth` (`#0A0F0B`) — légèrement plus sombre pour l'immersion.
- Les cards média utilisent `surface_card` (`#181B26`).
- Jamais de blanc pur comme fond.

---

## 2. Typographie

| Rôle | Taille | Poids | Couleur |
|---|---|---|---|
| Titre écran | 22–26 sp | Bold (700) | `on_background` |
| Titre card | 15–16 sp | Medium (500) | `on_background` |
| Sous-titre / métadonnée | 12–13 sp | Regular (400) | `on_surface_variant` |
| Corps / description | 14 sp | Regular (400) | `on_surface` |
| Label bouton principal | 16 sp | Medium (500) | `#0F1117` (sombre sur vert) |
| Label bouton secondaire | 15 sp | Regular (400) | `on_background` |
| Caption / badge | 11–12 sp | Medium (500) | variable |

Police : `sans-serif` (Roboto système) — pas de police custom en v1.

---

## 3. Composants communs

### Bouton principal (CTA)
```
height: 56dp | cornerRadius: 12dp
backgroundTint: green_accent (#3DD68C)
textColor: #0F1117 (contraste fort)
textSize: 16sp | fontWeight: Medium
```

### Bouton outline / secondaire
```
height: 56dp | cornerRadius: 12dp
strokeColor: outline (#3A3D4F)
textColor: on_background (#FFFFFF)
textSize: 15sp
```

### Champ texte (TextInputLayout OutlinedBox)
```
boxStrokeColor: outline → green_accent (focused)
hintTextColor: on_surface_variant
textColor: on_background
cornerRadius: 10dp
```

### Card média
```
background: surface_card (#181B26)
cornerRadius: 10dp
padding: 0dp (image pleine largeur en haut)
elevation: 0dp (design plat)

Structure :
  ┌────────────────────────┐
  │  Thumbnail (16:9)      │
  │  [durée en bas-droite] │
  ├────────────────────────┤
  │  Titre (1 ligne, bold) │
  │  Sous-titre / chemin   │
  │  [icône source] [taille]│
  └────────────────────────┘
```

### Chip / Badge
```
background: surface_variant | cornerRadius: 20dp | padding: 4dp 10dp
Texte: 12sp Medium | couleur selon contexte
```

### ProgressBar lecture
```
height: 3dp | cornerRadius: 2dp
trackColor: outline | indicatorColor: green_accent
```

### Bottom Navigation Bar
```
background: surface (#1A1D27)
indicatorColor: green_container
selectedIconColor: green_accent
unselectedIconColor: on_surface_variant
labelTextSize: 11sp
elevation: 8dp
```

---

## 4. Navigation globale

### Bottom Navigation — 4 onglets

| # | Icône | Label | Fragment |
|---|---|---|---|
| 1 | `ic_home` | Accueil | `HomeFragment` |
| 2 | `ic_network` | Réseau | `NetworkFragment` |
| 3 | `ic_folder` | Local | `BrowserFragment` |
| 4 | `ic_settings` | Réglages | `SettingsFragment` |

> Le Lecteur (`PlayerActivity`) est une Activity séparée, lancée par-dessus la Bottom Nav.

### Top Tabs (sur l'écran Accueil)

```
TabLayout (scrollable si besoin) :
  Tab 1 : "Tous"
  Tab 2 : "Réseau"    ← affiché seulement si un partage réseau est configuré
  Tab 3 : "Local"
  Tab 4 : "Récents"
```

### Graph de navigation

```
nav_graph.xml
├── loginFragment (start si non connecté)
│   ├── → registerFragment
│   └── → forgotPasswordFragment
└── homeFragment (start si connecté) ←── destination après auth

MainActivity (AppCompatActivity)
└── NavHostFragment (nav_graph)

PlayerActivity (Activity séparée, plein écran paysage)
└── lancée via Intent depuis toute card média
```

---

## 5. Écran 1 — Accueil (`HomeFragment`)

### Structure
```
┌─────────────────────────────────────┐
│ AppBar                              │
│   [Logo]  "Blaze Player"  [🔍] [👤] │
├─────────────────────────────────────┤
│ TabLayout : Tous | Réseau | Local | Récents │
├─────────────────────────────────────┤
│                                     │
│  ── Reprendre ── (si en cours)      │
│  ┌──────────────────────────────┐   │
│  │ Thumbnail large (16:9)       │   │
│  │ Titre · barre progression    │   │
│  └──────────────────────────────┘   │
│                                     │
│  ── Récents ──                      │
│  RecyclerView horizontal →          │
│  [Card] [Card] [Card] [Card]        │
│                                     │
│  ── Tous les fichiers ──            │
│  RecyclerView grille 2 colonnes     │
│  ┌──────┐ ┌──────┐                 │
│  │ Card │ │ Card │                 │
│  └──────┘ └──────┘                 │
│                                     │
├─────────────────────────────────────┤
│ [🏠] [📡] [📁] [⚙️]  ← Bottom Nav  │
└─────────────────────────────────────┘
```

### Comportement
- Tab "Tous" : médias locaux + réseau, triés par date de modification
- Tab "Réseau" : uniquement SMB/DLNA
- Tab "Local" : uniquement `MediaStore`
- Tab "Récents" : 20 derniers lus (persisté en DataStore)
- Section "Reprendre" : visible si position sauvegardée > 5 s
- Pull-to-refresh

### Card média (grille 2 colonnes)
```
Thumbnail 16:9 avec overlay durée (bas-droite, fond #00000080, 11sp)
Titre : 15sp Medium, 1 ligne, ellipsize end
Chemin : 12sp, on_surface_variant, 1 ligne
Icône source (SMB/DLNA/Local) + taille
```

---

## 6. Écran 2 — Réseau (`NetworkFragment`)

### Structure
```
┌─────────────────────────────────────┐
│ AppBar : "Réseau"         [+ Ajouter] │
├─────────────────────────────────────┤
│                                     │
│  ── Partages SMB ──                 │
│  ┌───────────────────────────────┐  │
│  │ [🖥] Nom du partage           │  │
│  │     192.168.1.x / share       │  │
│  │                   [→ Ouvrir]  │  │
│  └───────────────────────────────┘  │
│  [+ Ajouter un partage SMB]         │
│                                     │
│  ── Appareils DLNA ──               │
│  ┌───────────────────────────────┐  │
│  │ [📺] Nom de l'appareil DLNA  │  │
│  │     UPnP Media Server         │  │
│  └───────────────────────────────┘  │
│  [Recherche SSDP en cours…]         │
│                                     │
│  État vide :                        │
│  [Icône réseau 80dp]                │
│  "Aucun partage configuré"          │
│  [Bouton "Ajouter un partage"]      │
│                                     │
└─────────────────────────────────────┘
```

### Dialog "Ajouter un partage SMB" (BottomSheet)
```
Champs : Nom affiché, Hôte/IP, Nom du partage (\\host\share),
         Utilisateur (optionnel), Mot de passe (optionnel)
Boutons : [Tester la connexion] → [Enregistrer]
Fond : surface (#1A1D27) | cornerRadius top: 20dp
```

---

## 7. Écran 3 — Local / Navigateur (`BrowserFragment`)

### Structure
```
┌─────────────────────────────────────┐
│ AppBar                              │
│  [←] /Storage/Films/    [🔍] [⋮]  │
├─────────────────────────────────────┤
│ Breadcrumb : / > Storage > Films    │
├─────────────────────────────────────┤
│ Tri : [Nom ▼] [Date] [Taille] [Type]│
├─────────────────────────────────────┤
│                                     │
│  ┌───────────────────────────────┐  │
│  │ [📁] Dossier Films            │  │
│  │      42 éléments              │  │
│  └───────────────────────────────┘  │
│  ┌───────────────────────────────┐  │
│  │ [🎬] film.mkv                 │  │
│  │      2h14min · 4,2 Go · MKV   │  │
│  │      [██░░░░] 45% regardé     │  │
│  └───────────────────────────────┘  │
│                                     │
└─────────────────────────────────────┘
```

### Comportement
- Navigation dans l'arborescence
- Longpress → menu contextuel (Lire, Infos, Partager)
- Icône différente : 📁 dossier / 🎬 vidéo / 🎵 audio / 📄 sous-titre
- Badge de progression si déjà regardé (barre verte en bas de la card)

---

## 8. Écran 4 — Lecteur (`PlayerActivity`)

### Structure plein écran paysage
```
┌──────────────────────────────────────────────────────┐
│                                                      │
│                  [Vidéo ExoPlayer]                   │
│                                                      │
│  ┌── Overlay (visible 3 s, tap pour afficher) ──┐   │
│  │ [←]  Titre du fichier.mkv   [CC][📡][⋮]     │   │
│  │                                               │   │
│  │       [⏮-10s]  [⏸/▶ 80dp]  [+10s⏭]          │   │
│  │                                               │   │
│  │  0:32:14 ━━━━━━━●──────────── 1:54:06        │   │
│  │  [🔊──────]  [Piste audio ▼]  [Sous-titres ▼] │  │
│  │                                    [🔒 Lock]  │   │
│  └───────────────────────────────────────────────┘   │
│                                                      │
│  [Sous-titres centrés en bas, 18sp, fond semi-trans] │
│                                                      │
└──────────────────────────────────────────────────────┘
```

### Gestes
| Geste | Action |
|---|---|
| Tap | Afficher/masquer controls |
| Double tap gauche | Rewind -10 s |
| Double tap droit | Forward +10 s |
| Swipe vertical gauche | Luminosité |
| Swipe vertical droite | Volume |
| Pinch | Zoom (crop/fit) |
| Swipe bas | Fermer le lecteur |

### Panneau Sous-titres (BottomSheet)
```
Liste pistes disponibles (internes + SRT/ASS externes)
Décalage : [-2s] [±slider] [+2s]
Taille : [A-] [A+]  |  Couleur texte + fond
```

### Panneau Pistes audio (BottomSheet)
```
Liste des pistes audio du fichier
Langue détectée automatiquement
```

---

## 9. Écran 5 — Réglages (`SettingsFragment`)

### Structure
```
┌─────────────────────────────────────┐
│ AppBar : "Réglages"                 │
├─────────────────────────────────────┤
│                                     │
│  ── Compte ───────────────────────  │
│  [Avatar 40dp]  email@exemple.com   │
│                 Membre Pro ✓        │
│                 (ou Essai : Xj rest)│
│  [Gérer l'abonnement →]             │
│                                     │
│  ── Lecture ──────────────────────  │
│  Décodeur matériel     [ON/OFF]     │
│  Reprendre auto        [ON/OFF]     │
│  Vitesse par défaut    [1.0×  ▼]   │
│  Qualité streaming     [Auto  ▼]   │
│                                     │
│  ── Sous-titres ──────────────────  │
│  Taille                [M ▼]        │
│  Police                [Roboto ▼]   │
│  Couleur texte         [⬜ Blanc]   │
│  Couleur fond          [⬛ Noir 50%]│
│  Langue préférée       [Auto ▼]    │
│                                     │
│  ── Réseau ───────────────────────  │
│  Partages SMB          [Gérer →]   │
│  Timeout connexion     [10 s ▼]    │
│                                     │
│  ── App ──────────────────────────  │
│  Thème                 [Sombre ▼]  │
│  Langue                [Français ▼]│
│  Version               1.0.0        │
│  [Se déconnecter]                   │
│                                     │
└─────────────────────────────────────┘
```

---

## 10. Écran 6 — Paywall (`PaywallFragment`)

### Palette spécifique
```
background: background_auth (#0A0F0B)
CTA: green_accent (#3DD68C), textColor: #0F1117
Badge essai: yellow_accent (#F0B429)
```

### Structure
```
┌─────────────────────────────────────┐
│                      [✕ Fermer]     │
│                                     │
│      🔥  Blaze Player Pro           │
│         (28sp bold, on_background)  │
│                                     │
│  ┌─────────────────────────────┐    │
│  │ ✓ Lecture SMB/DLNA illimitée│    │
│  │ ✓ Sous-titres avancés (SRT) │    │
│  │ ✓ Chromecast                │    │
│  │ ✓ Décodeur matériel HW      │    │
│  │ ✓ Aucune pub, accès à vie   │    │
│  └─────────────────────────────┘    │
│   (fond: surface_card, radius: 12dp)│
│                                     │
│  ╔══ Achat unique ════════════╗     │
│  ║   Accès à vie   3,99 €    ║     │
│  ║   Paiement unique, pas d'abo║    │
│  ╚════════════════════════════╝     │
│                                     │
│  [ ⭐ Essai en cours — X jours ]    │
│         (badge yellow_accent)       │
│                                     │
│  [    Acheter — 3,99 €    ]  ← vert │
│  [   Restaurer les achats  ]← outline│
│                                     │
│  Conditions d'utilisation           │
│  Politique de confidentialité       │
│                                     │
└─────────────────────────────────────┘
```

### États
| État | Affichage |
|---|---|
| Dans l'essai (< 15j) | Badge jaune "Essai — X jours restants" |
| Essai expiré | Badge rouge "Essai expiré — Passez Pro" |
| Pro actif | Écran de confirmation, CTA masqué |

---

## 11. Écran 7 — Auth

### Palette spécifique
```
background: background_auth (#0A0F0B)
CTA: green_accent (#3DD68C), textColor: #0F1117
Liens: blue_accent (#378ADD)
Secondaire: on_surface_variant (#9B9DB3)
```

### Login
```
[Logo 72dp]
Blaze Player  (30sp bold)
Connectez-vous pour continuer  (14sp, on_surface_variant)

[ Adresse email                    ]
[ Mot de passe                👁  ]
                  Mot de passe oublié ? (blue_accent)

[       Se connecter (vert, 56dp)     ]
[LinearProgressIndicator si loading   ]

────────── ou ──────────

[ G  Continuer avec Google (outline) ]

Pas encore de compte ?  S'inscrire (green_accent bold)
```

### Register
```
← (back)
Créer un compte  (26sp)
Profitez de 15 jours d'essai gratuit

[ Email ]
[ Mot de passe             👁 ]
[ Confirmer le mot de passe 👁 ]

[   Créer mon compte (vert)   ]
── ou ──
[ G  Continuer avec Google   ]
```

### Forgot Password
```
← (back)
Mot de passe oublié  (26sp)
Entrez votre email et nous vous enverrons
un lien de réinitialisation.

[ Adresse email ]

[   Envoyer le lien (vert)   ]

[✓ Email envoyé ! Vérifiez votre boîte mail.]
  (badge bg_success_chip, visible après envoi)
```

---

## 12. Espacements & Rayons

| Token | Valeur |
|---|---|
| Marge latérale standard | `24dp` |
| Marge latérale dense | `16dp` |
| Espacement vertical sections | `24dp` |
| Espacement vertical items | `12dp` |
| Corner radius cards | `10dp` |
| Corner radius boutons | `12dp` |
| Corner radius chips | `20dp` |
| Corner radius BottomSheet (top) | `20dp` |
| Corner radius inputs | `10dp` |
| Corner radius thumbnail | `8dp` (top) |
| Hauteur bouton CTA | `56dp` |
| Hauteur Bottom Nav | `64dp` |
| Hauteur AppBar | `56dp` |

---

## 13. États d'interface

### Chargement
- **Listes** : skeleton shimmer (cards grises animées)
- **Boutons** : `LinearProgressIndicator` sous le bouton, bouton désactivé
- **Lecteur** : `CircularProgressIndicator` centré (green_accent)

### Vide (Empty State)
```
Icône SVG 80dp (on_surface_variant, 40% opacité)
Titre : 18sp Medium (on_surface)
Description : 14sp (on_surface_variant)
Bouton CTA optionnel
```

### Erreur
- Snackbar (`LENGTH_LONG`) avec message en français
- Champ invalide : `til.error = "message"` (rouge)

---

## 14. Icônes (Material Symbols Outlined, 24dp)

| Icône | Usage |
|---|---|
| `home` | Onglet Accueil |
| `wifi` / `router` | Onglet Réseau |
| `folder` | Onglet Local / Navigateur |
| `settings` | Onglet Réglages |
| `play_circle` | Lecture |
| `pause_circle` | Pause |
| `replay_10` / `forward_10` | Navigation lecteur |
| `closed_caption` | Sous-titres |
| `cast` | Chromecast |
| `lock` / `lock_open` | Verrouillage lecteur |
| `search` | Recherche |
| `account_circle` | Profil |
| `add` | Ajouter |
| `star` | Badge Pro / essai |
| `check_circle` | Succès |
| `error` | Erreur |
| `arrow_back` | Retour |

---

## 15. Logique Pro / Trial

| Fonctionnalité | Free | Essai (15j) | Pro |
|---|---|---|---|
| Fichiers locaux | ✓ | ✓ | ✓ |
| SMB/DLNA | ✗ | ✓ | ✓ |
| Chromecast | ✗ | ✓ | ✓ |
| Sous-titres avancés (SRT/ASS) | Basique | ✓ | ✓ |
| Pistes audio multiples | ✓ | ✓ | ✓ |
| Décodeur hardware | ✓ | ✓ | ✓ |

### Calcul côté client
```kotlin
val trialDaysLeft = 15 - ((System.currentTimeMillis() - trialStartDate) / 86_400_000L).toInt()
val isTrialActive = trialDaysLeft > 0
val hasProAccess = isPro || isTrialActive

if (!hasProAccess) {
    findNavController().navigate(R.id.paywallFragment)
}
```

---

*Dernière mise à jour : Étape 2 — Auth implémenté.*
