# Worked example: the Dutch registers

One region's version of section 8 of the guide, "verify against the source of truth".
The specifics are Dutch — BAG for buildings and addresses, PDOK as the service that
publishes them — but **the method transfers**: find the authoritative register, learn
how its status values map onto OSM, page its API and index by a stable id, and measure
before you re-import geometry. Write the equivalent page for the country you map in.

## BAG via PDOK WFS

Fetch panden for a bbox and index by `identificatie`:

```bash
curl -sS -G 'https://service.pdok.nl/lv/bag/wfs/v2_0' \
  --data-urlencode 'service=WFS' --data-urlencode 'version=2.0.0' \
  --data-urlencode 'request=GetFeature' --data-urlencode 'typeName=bag:pand' \
  --data-urlencode 'outputFormat=application/json' --data-urlencode 'srsName=EPSG:4326' \
  --data-urlencode 'bbox=<minlat>,<minlon>,<maxlat>,<maxlon>,urn:ogc:def:crs:EPSG::4326' \
  --data-urlencode 'count=1000' --data-urlencode 'startIndex=0'
```

Page with `startIndex` until a response has fewer than `count` features.

**Filtering by `identificatie IN (...)` silently ignores the filter** and returns
arbitrary panden. Always page a bbox and index the result yourself.

`typeName=bag:verblijfsobject` gives address-level status. Its properties include
`openbare_ruimte`, `huisnummer`, `huisletter`, `toevoeging`, `postcode`, `status`,
`pandidentificatie` and `pandstatus` — enough to match OSM address nodes by
street + housenumber as an independent check on point-in-polygon matching.

## Pand status → what it means for OSM

| BAG status | In OSM |
|---|---|
| Bouwvergunning verleend | **Nothing is built.** The NL import guideline says do not import these at all. |
| Bouw gestart | First status that justifies `building=construction`. |
| Pand in gebruik | Normal `building=*`. |
| Verbouwing pand | May legitimately overlap a neighbouring pand that is not withdrawn yet — a source artefact, do not reshape it away. |
| Sloopvergunning verleend / Pand gesloopt | Demolition; check imagery before removing. |

`bouwjaar` is the *planned* year while a permit is outstanding, so a recent
`start_date` on an empty plot is not evidence of anything.

Address nodes for permit-only panden are "Verblijfsobject gevormd" and go with the pand.

## Geometry: measure, do not re-import wholesale

Decide **per pand** by measuring the Hausdorff distance between the OSM ring and the
BAG outer ring. Re-import only above **0.5 m**; below that it is measurement noise and
rewriting churns node history.

Measured on Schiermonnikoog: of 1772 panden with a `ref:bag`, 1697 were within 10 cm and
only 37 (2%) exceeded 0.5 m — the same rate for the 2014 import lichting as for 2025.
**So `source:date` is not a staleness proxy.**

After a `replace_geometry`, check `shared_or_tagged_nodes_left_out_of_way`: an
`entrance=*` node or the top of a `highway=steps` that fell out must be moved onto the
new ring and re-inserted. Snap any remaining node more than 2 cm off the BAG ring, or
neighbouring panden end up with slivers. When a dropped node belongs to a neighbouring
pand, first check whether the two BAG rings actually touch — often they do not and OSM
had them wrongly glued.

## Imagery

| Layer | Coverage |
|---|---|
| PDOK Luchtfoto Beeldmateriaal 7,5cm (WMTS) | Whole country incl. Wadden Sea |
| Luchtfoto 2026 Quick Ortho 8cm RGB | Land only — stops at the Wadden Sea |
| Luchtfoto 2025 Ortho 5 en 8cm Infrarood | Vegetation work |

Other useful references: TopoTijdreis (historic), Rijkswaterstaat Kustlidar (elevation,
`source:ele`), RWS Zeegraskartering (seagrass), `natuurmonumenten.maps.arcgis.com`
(reserve boundaries).

## Other registries

- Liander distribution substations are **not** BAG panden — no `ref:bag`, use `ref:liander`.
- Coastline on the Wadden islands changes fast; carry `check_date` and compare against
  the newest ortho before redrawing. Several islands carry a `note` warning that Bing is
  too old to trace from.

---

# Appendix: France (IGN BD TOPO)

Same method, third country. The national building layer is served from the IGN geoplatform:

```bash
curl -sS -G 'https://data.geopf.fr/wfs/ows' \
  --data-urlencode 'service=WFS' --data-urlencode 'version=2.0.0' \
  --data-urlencode 'request=GetFeature' \
  --data-urlencode 'typeNames=BDTOPO_V3:batiment' \
  --data-urlencode 'srsName=urn:ogc:def:crs:EPSG::4326' \
  --data-urlencode 'bbox=<minlat>,<minlon>,<maxlat>,<maxlon>,urn:ogc:def:crs:EPSG::4326' \
  --data-urlencode 'resultType=hits'
```

Swap `resultType=hits` for `count=2000` plus `propertyName=nature,usage_1,etat_de_l_objet`
to classify without downloading geometry. Unlike ALKIS, `batiment` is a clean building
layer: over Pontrieux all 1462 features were buildings, 1461 `etat_de_l_objet=En service`
and one `En ruine`. `usage_1` distinguishes `Résidentiel`, `Annexe`, `Commercial et
services`; `nature` is mostly `Indifférenciée`.

Watch the age of what is already in OSM: French towns were bulk-imported from the
cadastre years ago, and the buildings carry it in `source`, e.g.
`cadastre-dgi-fr source : Direction Générale des Impôts - Cadastre. Mise à jour : 2011`.
In Pontrieux 88% of 1427 buildings came from the 2011 lichting and still matched the
current BDOrtho — old is not the same as wrong, so measure before re-importing.

Imagery and overlays in the JOSM catalogue: `fr.ign.bdortho` (BDOrtho IGN, the standard
aerial) and `Cadastre` (parcels). Both are found with `country: "FR"`.
