package fr.retrospare.blazeplayer.billing

/** Identifiants à configurer à l'identique dans le dashboard RevenueCat (projet "Blaze Player").
 *
 * - Entitlements : [ENTITLEMENT_PRO] et [ENTITLEMENT_PRO_PLUS].
 * - Produits Google Play (achat unique, non consommable) : [PRODUCT_PRO_LIFETIME] et
 *   [PRODUCT_PRO_PLUS_LIFETIME], à créer dans Play Console puis à rattacher dans RevenueCat
 *   à leur entitlement respectif (le produit Pro+ doit être rattaché aux DEUX entitlements
 *   pro et pro_plus, puisque Pro+ inclut Pro).
 * - Offering : [OFFERING_DEFAULT], contenant deux packages de type "Custom" nommés
 *   [PACKAGE_PRO_LIFETIME] et [PACKAGE_PRO_PLUS_LIFETIME].
 *
 * L'essai de 15 jours n'est pas géré par RevenueCat/Google Play (les produits sont des achats
 * uniques, qui ne supportent pas nativement de période d'essai côté store) : il reste géré
 * localement par UserRepository, exactement comme aujourd'hui. RevenueCat ne sert ici qu'à
 * traiter et valider les deux achats définitifs Pro / Pro+.
 */
object RevenueCatIds {
    const val ENTITLEMENT_PRO = "pro"
    const val ENTITLEMENT_PRO_PLUS = "pro_plus"

    const val PRODUCT_PRO_LIFETIME = "pro_lifetime"
    const val PRODUCT_PRO_PLUS_LIFETIME = "pro_plus_lifetime"

    const val OFFERING_DEFAULT = "default"
    const val PACKAGE_PRO_LIFETIME = "pro_lifetime"
    const val PACKAGE_PRO_PLUS_LIFETIME = "pro_plus_lifetime"
}
