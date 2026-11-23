# OpenVFR — Electronic Flight Bag (EFB) VFR Open Source

Application Android de navigation aéronautique VFR conçue en **Kotlin / Jetpack Compose (Material You)** et **MapLibre GL**.

## Fonctionnalités incluses

1. **Fusion de capteurs haute précision**
   - Filtre de Kalman à 3 états (`[z_AMSL, Vz, biais_baro]`) couplant baromètre 50 Hz et GPS corrigé du géoïde **EGM**.
   - Compensation d'inclinaison AHRS, déclinaison magnétique mondiale **WMM** (`GeomagneticField`), estimation continue du vecteur vent en vol et mode **Simulateur de vol** intégré.
2. **Importation & Calibration de cartes PDF (VAC / OACI)**
   - Solveur multi-modèles : similitude (2 pts), affine (3 pts), régression bilinéaire Lambert ($\ge 4$ pts) avec erreur **RMSE** en mètres en temps réel.
   - Détourage des marges blanches VAC, gestion multi-calques et opacité indépendante.
3. **Espaces aériens OpenAir & Parseur géométrique NOTAM / SUP AIP**
   - Support complet du format OpenAir (`AC`, `AN`, `AL`, `AH`, `AF`, `DP`, `DC`, `DA`, `DB`), inspection verticale au toucher, filtre de plafond et alertes proactives à +2 min.
   - Extraction automatique des coordonnées OACI et rayons (`RADIUS X NM`) depuis les bulletins NOTAM bruts.
4. **Préparation de vol, Profil vertical, Météo & Devis**
   - Triangle des vitesses exact, recalage automatique des heures estimées (`HE`) sur saisie d'heure réelle (`HR`), export **GPX** / **Garmin FPL** / **Briefing**.
   - Coupe verticale relief MNT + trajectoire, décodeur **METAR/TAF**, devis carburant réglementaire VFR jour/nuit, enveloppe **Masse & Centrage** et **anneau de finesse panne moteur 3D** tenant compte du relief et du vent.
5. **Connectivité Avionique GDL90**
   - Récepteur UDP port `4000` compatible Stratux, SkyEcho et SafeSky.

## Compilation automatique sur GitHub

1. Décompressez cette archive et poussez le contenu à la racine d'un dépôt GitHub.
2. Ouvrez l'onglet **Actions** -> **Build OpenVFR APK**.
3. Téléchargez l'artefact **`OpenVFR-debug`** contenant `app-debug.apk`.
