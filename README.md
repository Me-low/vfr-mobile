# VFR Nav Mobile (V1 Complète)

Application mobile Android VFR autonome et hybride compilée automatiquement sur GitHub Actions.

## Fonctionnalités intégrées
- **Carte OACI GPU (MapLibre GL JS)** : rendu matériel 60/120 FPS, tuiles `512x512 px` découpées nativement à la demande via `PdfRenderer` + cache RAM/disque, projection conforme de Lambert (RGF93) et recalibration tactile au réticule.
- **Télémétrie fusionnée** : GPS temps réel interpolé, vitesse sol (`kt`), double direction (bascule automatique boussole compensée / route GPS + vecteur d'anticipation 5 min), altitude GPS MSL et altitude barométrique (`QNH` / `FL`).
- **Cartes VAC superposées à l'échelle** : extraction automatique des points de report (`TXT 01`) et graduations de graticule (`APP 01` / `ATT 01`) via PDF.js + secours manuel sur 2 points + opacité réglable en vol.
- **Base aérodrome & contrôle AIRAC** : calcul automatique du cycle AIRAC 28 jours, recherche terrain/VOR, déroutement 1-tap vers les 5 aérodromes les plus proches.
- **Mode autonome + Hybride PC** : import direct d'un briefing JSON exporté depuis ton outil PC (`sample-pc-briefing.json` inclus pour tester) et enregistrement de trace GPX.
