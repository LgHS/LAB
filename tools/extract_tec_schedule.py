#!/usr/bin/env python3
"""
Extrait du GTFS TEC ce dont le tableau de bord a besoin pour les quais de dashboard/transport.json :

- dashboard/tec_schedule.csv : horaire théorique de chaque course à nos quais.
  Le flux GTFS-RT officiel TEC ne donne que des retards (pas d'heures absolues) :
  l'app en a besoin pour calculer l'heure réelle.
- dashboard/tec_last_trips.csv : pour chaque jour de service, la dernière course de chaque
  ligne à chaque quai (pour afficher « dernier »).
- dashboard/generated.json : version (début/fin du GTFS + quais). Ne change que si le GTFS
  ou les quais changent : l'app ne retélécharge les CSV que dans ce cas.

Lancé par la GitHub Action (.github/workflows/dashboard-tec.yml) à chaque modification de
transport.json et chaque semaine.

Usage : python3 tools/extract_tec_schedule.py [chemin/vers/TEC-GTFS.zip]
"""
import collections
import csv
import datetime
import io
import json
import pathlib
import sys
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "dashboard"
CONFIG = OUT_DIR / "transport.json"
# CSV brut : l'APK compresse lui-même (et AGP décompresserait un .gz embarqué)
SCHEDULE = OUT_DIR / "tec_schedule.csv"
LAST_TRIPS = OUT_DIR / "tec_last_trips.csv"
GENERATED = OUT_DIR / "generated.json"
GTFS_URL = "https://opendata.tec-wl.be/Current%20GTFS/TEC-GTFS.zip"


def rows(z, name):
    return csv.DictReader(io.TextIOWrapper(z.open(name), "utf-8-sig"))


def parse_date(s):
    return datetime.date(int(s[:4]), int(s[4:6]), int(s[6:]))


def active_dates(z):
    """service_id → ensemble des dates où il circule (calendar + exceptions)."""
    dates = collections.defaultdict(set)
    days = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
    for r in rows(z, "calendar.txt"):
        d, end = parse_date(r["start_date"]), parse_date(r["end_date"])
        while d <= end:
            if r[days[d.weekday()]] == "1":
                dates[r["service_id"]].add(d)
            d += datetime.timedelta(days=1)
    for r in rows(z, "calendar_dates.txt"):
        d = parse_date(r["date"])
        if r["exception_type"] == "1":
            dates[r["service_id"]].add(d)
        else:
            dates[r["service_id"]].discard(d)
    return dates


stops = {s["id"] for s in json.loads(CONFIG.read_text(encoding="utf-8"))["tec"]["stops"]}
if not stops:
    sys.exit("Aucun quai TEC dans transport.json")

if len(sys.argv) > 1:
    data = pathlib.Path(sys.argv[1]).read_bytes()
else:
    print(f"Téléchargement {GTFS_URL}…")
    data = urllib.request.urlopen(GTFS_URL).read()

with zipfile.ZipFile(io.BytesIO(data)) as z:
    feed_start = next(rows(z, "feed_info.txt")).get("feed_start_date", "")
    feed_end = max(r["end_date"] for r in rows(z, "calendar.txt"))
    line_of_route = {r["route_id"]: r["route_short_name"] for r in rows(z, "routes.txt")}
    trips = {r["trip_id"]: (line_of_route[r["route_id"]], r["service_id"]) for r in rows(z, "trips.txt")}
    service_dates = active_dates(z)

    # trip_id → [(stop_id, séquence, secondes depuis minuit du jour de service)]
    schedule = collections.defaultdict(list)
    with z.open("stop_times.txt") as f:
        reader = csv.reader(io.TextIOWrapper(f, "utf-8-sig"))
        header = next(reader)
        ti, si, qi, di = (header.index(c) for c in ("trip_id", "stop_id", "stop_sequence", "departure_time"))
        for r in reader:
            if r[si] in stops:
                h, m, s = map(int, r[di].split(":"))
                schedule[r[ti]].append((r[si], int(r[qi]), h * 3600 + m * 60 + s))

count = 0
with open(SCHEDULE, "w", encoding="utf-8") as out:
    out.write(f"# valid_until={feed_end}\n")
    for trip, entries in schedule.items():
        for stop, seq, secs in entries:
            out.write(f"{trip},{stop},{seq},{secs}\n")
            count += 1

# Dernière course par (jour, quai, ligne)
last = {}
for trip, entries in schedule.items():
    line, service = trips[trip]
    for day in service_dates.get(service, ()):
        for stop, _, secs in entries:
            key = (day, stop, line)
            if key not in last or secs > last[key][0]:
                last[key] = (secs, trip)

with open(LAST_TRIPS, "w", encoding="utf-8") as out:
    out.write(f"# valid_until={feed_end}\n")
    for (day, stop, _), (_, trip) in sorted(last.items()):
        out.write(f"{day:%Y%m%d},{trip},{stop}\n")

GENERATED.write_text(json.dumps({
    "gtfsStart": feed_start,
    "validUntil": feed_end,
    "stops": sorted(stops),
}, indent=2) + "\n", encoding="utf-8")

for path, n in ((SCHEDULE, count), (LAST_TRIPS, len(last))):
    print(f"{path.relative_to(ROOT)} : {n} lignes, {path.stat().st_size // 1024} Ko")
print(f"Valide jusqu'au {feed_end}")
