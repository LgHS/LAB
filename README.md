# Little Android Brother

Tableau de bord Android TV / tablette (paysage) du Liège Hackerspace : caméras UniFi,
météo, musique moOde, prochains départs TEC et SNCB. Tourne comme écran d'accueil d'une box.

## Build

```bash
./gradlew assembleRelease   # → app/build/outputs/apk/release/app-release.apk
```

Installer le build **release** sur la box : en debug, l'app est ~50× plus lente (le
chargement des horaires TEC passe de 0,4 s à 20 s).

Réglages privés dans `local.properties` (ignoré par git, jamais dans ce dépôt public) :

```properties
tec.apiKey=…                          # clé GTFS-RT TEC (https://gtfsrt.tectime.be)
camera.porte=rtsps://…:7441/…?enableSrtp   # URLs RTSPS UniFi Protect (contiennent le jeton)
camera.sas=rtsps://…:7441/…?enableSrtp
nuki.token=…                          # jeton de l'API HTTP du bridge Nuki (état de la porte)
```

Sans `nuki.token`, l'app se compile mais la tuile Porte affiche « Nuki ? ».

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
| Musique | moOde : MPD (`idle`) + renderers Spotify/AirPlay/Deezer | instantané / 2 s |
| Bus | GTFS-RT TEC (retards) + horaires générés | 30 s |
| Trains | [iRail](https://api.irail.be) | 60 s |
| Météo | [Open-Meteo](https://open-meteo.com) | 10 min |
| Arrêts / trajets | `dashboard/` de ce dépôt | 1 h |
