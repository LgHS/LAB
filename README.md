# Little Android Brother

Tableau de bord Android TV / tablette (paysage) du Liège Hackerspace : caméras UniFi,
météo, musique moOde, prochains départs TEC et SNCB. Tourne comme écran d'accueil d'une box.

## Build

```bash
./gradlew assembleRelease   # → app/build/outputs/apk/release/app-release.apk
```

Installer le build **release** sur la box : en debug, l'app est ~50× plus lente (le
chargement des horaires TEC passe de 0,4 s à 20 s).

## Secrets

Les secrets ne sont pas dans l'APK (dépôt et releases publics). Fichier `secrets.properties`,
jamais dans ce dépôt :

```properties
tec.apiKey=…                          # clé GTFS-RT TEC (https://gtfsrt.tectime.be)
camera.porte=rtsps://…:7441/…?enableSrtp   # URLs RTSPS UniFi Protect (contiennent le jeton)
camera.sas=rtsps://…:7441/…?enableSrtp
nuki.token=…                          # jeton de l'API HTTP du bridge Nuki (état de la porte)
```

À déposer une fois sur la box, puis redémarrer l'app :

```bash
adb shell content write --uri content://be.lghs.lab.secrets/secrets < secrets.properties
adb shell am force-stop be.lghs.lab && adb shell am start -n be.lghs.lab/.MainActivity
```

Le fichier va dans le stockage privé de l'app (le stockage partagé `Android/data` n'est pas
accessible à adb sur la box) et survit aux mises à jour. Seul adb peut l'écrire, personne ne
peut le relire par ce biais. Pour changer une valeur, renvoyer le fichier complet.

En dev, les mêmes clés dans `local.properties` (ignoré par git) servent de valeurs par défaut,
compilées dans l'APK local. Sans `nuki.token`, la tuile Porte affiche « Nuki ? ».

## Release (GitHub Actions)

Le workflow [`android.yml`](.github/workflows/android.yml) compile et teste chaque push. Un tag
`vX.Y.Z` publie en release GitHub un APK signé avec la clé release :

```bash
git tag v0.4.0 && git push origin v0.4.0
```

`versionCode` est dérivé du tag (majeur×10000 + mineur×100 + patch, mineur et patch < 100) :
chaque tag doit être plus grand que le précédent, sinon Android refuse la mise à jour. Ne poser
les tags que sur `main`.

Secrets du dépôt (Settings → Secrets and variables → Actions) : `RELEASE_KEYSTORE_BASE64`
(keystore encodé en base64), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`. **Ne jamais perdre ce keystore** : un APK signé avec une autre clé ne
peut pas mettre à jour l'app installée (il faut désinstaller). Pour signer en local avec la même
clé : `release.storeFile`, `release.storePassword`, `release.keyAlias`, `release.keyPassword`
dans `local.properties`. Sans clé, le build release est signé avec la clé debug.

### Mise à jour automatique

L'app vérifie la dernière release GitHub une minute après son démarrage puis toutes les 15 min.
Si son tag est plus récent, elle télécharge l'APK, vérifie qu'il est signé avec la même clé,
l'installe et se relance. À autoriser une fois sur la box :

```bash
adb shell appops set be.lghs.lab REQUEST_INSTALL_PACKAGES allow
```

Sans confirmation à partir d'Android 12, une fois que l'app est son propre installeur : la
première mise à jour après un `adb install` affiche une confirmation à l'écran (OK à la
télécommande). Avant Android 12, la confirmation s'affiche à chaque mise à jour. Une box
installée avec un APK signé par une autre clé (clé debug) ne se met pas à jour : la
désinstaller une fois, puis installer l'APK de la release. Les builds debug ne se mettent
jamais à jour. Journal : `adb logcat -s Updater`.

La version installée est affichée en permanence en bas à gauche de l'écran : `v0.4.0` pour une release, suffixe `-dev`
pour un build local (sans tag), `debug` pour un build debug.

Réglages locaux (moOde, bridge Nuki, météo) : `app/src/main/java/be/lghs/lab/Config.kt`.

## Arrêts TEC et trains SNCB

**À modifier : [`dashboard/transport.json`](dashboard/transport.json)**, directement sur
GitHub. L'app le relit au démarrage puis toutes les heures, sans réinstallation.

- `tec.stops` : quais TEC (`stop_id` du GTFS, un quai = un sens). `lines` : lignes à afficher
  à ce quai → libellé de destination ; une ligne absente n'est pas affichée. Ordre = priorité :
  un bus passant par plusieurs quais est affiché au premier où sa ligne est listée.
- `sncb.routes` : trains directs entre deux gares (noms
  [iRail](https://api.irail.be/stations/?format=json&lang=fr)).

Trouver un quai : `stops.txt` du [GTFS TEC](https://opendata.tec-wl.be/Current%20GTFS/).

Le flux temps réel TEC ne contient que des retards : l'app a besoin de l'horaire théorique
des quais. La GitHub Action [`dashboard-tec.yml`](.github/workflows/dashboard-tec.yml) le
régénère (`dashboard/tec_*.csv`, `generated.json`, **à ne pas éditer**) à chaque modification
de `transport.json` et chaque lundi. Après un push, attendre la fin de l'Action (onglet
Actions) : sans horaires, les bus d'un nouveau quai n'apparaissent pas.

L'app garde en cache la dernière version valide et embarque `dashboard/` comme valeurs par
défaut. En local : `python3 tools/extract_tec_schedule.py [TEC-GTFS.zip]`.

## Installation sur la box (launcher)

L'app se déclare écran d'accueil (`HOME`) : elle démarre à l'allumage et sur la touche Home.

```bash
adb connect <ip-de-la-box>
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package set-home-activity be.lghs.lab/.MainActivity
```

Sur **Android TV / Google TV**, le launcher d'origine a une priorité système et garde la
touche Home : il faut le désactiver (réversible avec `pm enable`).

```bash
# Android TV
adb shell pm disable-user --user 0 com.google.android.tvlauncher
# Google TV
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

Secours si l'app n'est pas launcher : démarrage au boot, qui demande depuis Android 10
l'autorisation d'affichage par-dessus les autres apps :

```bash
adb shell appops set be.lghs.lab SYSTEM_ALERT_WINDOW allow
```

**Sortir vers les réglages Android** : touche Menu/Réglages de la télécommande, ou appui
long sur OK. La touche Retour ne quitte pas l'écran d'accueil.

## Sources de données

| Donnée | Source | Rafraîchissement |
|---|---|---|
| Caméras | UniFi Protect, RTSP (port 7447, converti depuis l'URL RTSPS) | continu |
| Porte | Bridge Nuki, `/lockState` (vert : verrouillée et fermée, sinon cadre rouge) | 5 s |
| Livraison | Code livreur, décompte, « proche » en évidence | 15 s |
| Musique | moOde : MPD (`idle`) + renderers Spotify/AirPlay/Deezer | instantané / 2 s |
| Bus | GTFS-RT TEC (retards) + horaires générés | 30 s |
| Trains | [iRail](https://api.irail.be) | 60 s |
| Météo | [Open-Meteo](https://open-meteo.com) | 10 min |
| Arrêts / trajets | `dashboard/` de ce dépôt | 1 h |
