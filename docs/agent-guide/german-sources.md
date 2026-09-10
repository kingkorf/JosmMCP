# Worked example: the German registers (NRW)

A second version of section 8 of the guide, "verify against the source of truth",
alongside [`dutch-sources.md`](dutch-sources.md). The specifics are North
Rhine-Westphalia; other Bundesländer publish the same ALKIS content under their own
endpoints, and the method is the same everywhere.

## The pattern: raster to look, WFS to count

The JOSM catalogue offers ALKIS as a **WMS overlay**, which is right for eyeballing —
toggle it over the aerial to see whose outline is whose. It cannot answer "how many".
For that, query the same register's **WFS** and count. Neither alone is enough:
the overlay gives you confidence in individual outlines, the WFS gives you a number you
can defend.

## ALKIS via WFS

```bash
curl -sS -G 'https://www.wfs.nrw.de/geobasis/wfs_nw_alkis_vereinfacht' \
  --data-urlencode 'service=WFS' --data-urlencode 'version=2.0.0' \
  --data-urlencode 'request=GetFeature' \
  --data-urlencode 'typeNames=ave:GebaeudeBauwerk' \
  --data-urlencode 'srsName=urn:ogc:def:crs:EPSG::4326' \
  --data-urlencode 'bbox=<minlat>,<minlon>,<maxlat>,<maxlon>,urn:ogc:def:crs:EPSG::4326' \
  --data-urlencode 'count=5000'
```

- `resultType=hits` returns only the count — use it first to size the job.
- `propertyName=funktion,gebnutzbez` drops the geometry, which turns a multi-megabyte
  response into something a script can chew through quickly.
- Feature types in this service: `ave:Flurstueck` (parcels), `ave:GebaeudeBauwerk`
  (buildings **and** structures), `ave:Nutzung` (land use), `ave:VerwaltungsEinheit`.
- The service has no addresses. Its native CRS is EPSG:25832; ask for 4326 explicitly.

## The trap: GebaeudeBauwerk is not "buildings"

**Filter on `gebnutzbez`, or your numbers are wrong.** Measured over Medebach:

| `gebnutzbez` | count |
|---|---|
| Gebäude | 3661 |
| Sonstiges Bauwerk oder sonstige Einrichtung | 606 |
| Bauteil | 38 |
| other (silos, sport, industry) | 14 |
| **total** | **4319** |

OSM had 3613 building ways there. Against the raw 4319 that reads as a 16% gap; against
the 3661 actual `Gebäude` it is **98.7% coverage** — essentially complete. The 606
"sonstiges" are mostly `funktion=Überdachung`, canopies that OSM maps only optionally.

`funktion` carries the detail worth comparing against OSM's building values:
`Wohngebäude`, `Gebäude zum Parken`, `Carport`, `Gebäude für Land- und Forstwirtschaft`,
`Gemischt genutztes Gebäude mit Wohnen`. In Medebach 1157 parking buildings plus 169
carports faced only 179 `building=garage` in OSM — the buildings were there, the type
was not. That is the shape of the gap in a well-mapped German town: detail, not coverage.

## Currency

Each feature carries `aktualit`. Sample it rather than assuming: in the Medebach town
centre 58 of 60 sampled buildings were last touched 2023-12-20 and 2 in 2025. A register
being "official" says nothing about it being current — principle 3, map what is on the
ground.

## Imagery and other layers

The NRW entries in the JOSM catalogue are named in the interface language but their ids
are stable: `DE-NRW-DOP` (aerial, WMTS), `DE-NRW-ALKIS` (cadastre, WMS),
`DE-NRW-vDOP` / `DE-NRW-iDOP` (true orthophotos), `nrw_dtm_wms` (hillshade), plus
infrared variants. Search `list_imagery` for `DE-NRW` or use `country: "DE"`.

## Before importing anything

German import rules live at
`https://wiki.openstreetmap.org/wiki/DE:Import` and the ALKIS-specific pages. Bulk
copying building functions out of ALKIS is an import, not a tag fix: it needs the
licence checked for the state in question and the community process followed.
