# Mapping OpenStreetMap through JOSM: a guide for agents

A working guide for any LLM agent driving a live [JOSM](https://josm.openstreetmap.de/)
through the [JosmMCP](../../README.md) MCP server. It covers how to use the tools
efficiently, and the OpenStreetMap conventions that decide what may be edited at all.

**Loading it.** The guide is plain Markdown and belongs in the agent's context before it
starts mapping.

- **Any MCP client**: put this file in the system prompt, or tell the agent to read
  `docs/agent-guide/README.md` first and the companion files when it needs them.
- **Claude Code**: the wrapper in `.claude/skills/osm-mapping/` loads it as a skill
  automatically inside a clone of this repository.
- Companion files, read on demand rather than up front:
  [`josmmcp-tools.md`](josmmcp-tools.md) (per-tool behaviour and pitfalls),
  [`osm-wiki.md`](osm-wiki.md) (lookup order and the good-practice principles),
  [`dutch-sources.md`](dutch-sources.md) — a worked example of section 8 for one
  country's registers; write the equivalent for yours.

Nothing here is client-specific: every instruction is about the JosmMCP tools and about
OpenStreetMap itself.

You drive a **live JOSM that a mapper is watching**. Every edit lands on their undo
stack and some calls pop a modal dialog on their screen. **There is no upload tool and
uploading is not yours to do** — finish the edits, report what is pending, let them upload.

Per-tool parameters and gotchas: `josmmcp-tools.md`.
A worked regional example (Dutch registers, thresholds, imagery coverage):
`dutch-sources.md`.

## 1. Orient before you touch anything

Start with `get_josm_state`. It gives the active layer, `downloaded_bounds`, whether
there are unsaved changes, `incomplete_stubs`, and **which layers already exist**. Never
download an area that is already loaded, and never add a layer before checking what is
there (see section 5). If the mapper names a place without loading it, check the bounds
first — they usually loaded it in a new layer.

`get_user_selection` tells you what they are pointing at. Use it when their request says
"this" or "these".

## 2. Protect the context budget

Results come back through the model, so unbounded reads are the main way these sessions
go wrong. Four habits, in order of impact:

1. **Always pass `fields`.** A bare `type:relation` search returns whole `name:*`
   dictionaries — a country or sea relation alone is tens of thousands of tokens.
   `["id","type","tags"]` is the normal minimum; drop `tags` when you only need ids.
2. **`output_path` for anything large.** `search_elements`, `read_elements`, `validate`,
   `find_duplicate_nodes` and `find_orphan_nodes` write the full result to a `.json` and
   return only a summary. Then process it with a script. This is the right default for
   any set over ~50 elements or any use of `include_geometry`. Needs the `files` group.
3. **`include_geometry` instead of N reads.** One `search_elements` with
   `include_geometry: true` replaces a loop of `read_way` calls when comparing outlines
   against an external source. Combine it with `fields` and `output_path`.
4. **`ids` plus a spatial filter in one call.** "Which of these lie in this area" is a
   single `search_elements`; the query is optional when `ids` is given.

`read_elements` reads many objects at once — prefer it over repeated `read_node`/`read_way`.

## 3. Batch every edit you can

Batching is not just faster: **each batch is one undo step and one confirmation dialog**,
so it respects the mapper's attention and is all-or-nothing.

| Instead of | Use |
|---|---|
| repeated `update_node` | `update_nodes` |
| repeated `create_node` | `create_nodes` |
| repeated `delete_*` | `delete_elements` |
| repeated `modify_tags` | `modify_tags_batch` |

Give every batch a clear `description`; it becomes the undo-history entry the mapper reads.

## 4. Respect the confirmation dialogs

JOSM shows a modal dialog before **deletes, `replace_geometry`, `remove_layer`,
`restart_josm`**, and before tag changes on a relation with over 100 members or batches
over 200 elements. Default timeout 60 s, **no answer means denied**.

- Say what you are about to do *before* the call, so the mapper is at the keyboard.
- A denial returns an error and changes nothing. Do not retry the same call; ask.
- Bulk deletes need your own confirmation too: present the count and the evidence first.
  **A precedent from an earlier area is not authorisation for a new one.**

Tools can also be switched off per group (read, view, tags, geometry, delete, history,
files, download) or globally by read-only mode. If a call fails as disabled, report it —
do not route around it with another tool.

## 5. Layers

The list from `get_josm_state` / `set_layer_visibility` is **top-to-bottom order**.

- **Look at what is loaded before adding anything.** Work with the layers that are
  already there. Only when they genuinely do not suffice — wrong area, wrong date,
  wrong kind of source — look at what else is available (`list_imagery`) and add that,
  saying why. Then hide what you are not using, so you are not reading a render with
  layers piled on top of each other.
- **Order matters**: index 0 is the top, drawn over everything below it. A layer under
  an opaque one is invisible — that is a stacking problem, not an empty layer. Fix it
  with `move_layer` (`above`/`below` another layer by name, `direction`
  `up`/`down`/`top`/`bottom`, or an absolute `position`), or hide what is on top.
- **Toggle the reference overlays** the mapper keeps loaded — a cadastral or land
  registry WMS, a large-scale topographic layer, a building-register layer. They are
  there to be switched on and off: use them to work out which outline comes from which
  source, and to tell a register's geometry apart from what someone traced by hand.
- **Hide the data layer** temporarily to read what is underneath without the OSM
  rendering over it; turn it back on after.
- `remove_layer` refuses a data layer with unsaved changes unless forced, and never the
  active one. Prefer hiding over removing.

## 6. Captures

- Do **not** pass `restore_view: true`. Leave JOSM where the capture zoomed.
- `wait_ms` 5000–8000 for WMS/WMTS; the default 1500 is too short for aerials.
- **Past roughly 0.1 m/px a WMTS may upsample a coarser tile and place it wrongly.** An
  apparent ~10 m offset at extreme zoom is usually this, not bad imagery. Re-capture
  wider before concluding anything is misaligned.
- Anchor conclusions to vector geometry — a register's rings, an overlay's outlines,
  OSM's own nodes — not to pixels.
- `select_elements` before capturing makes the objects under discussion visible in the
  render and in the mapper's own screen.

## 7. The OSM wiki is the first place you look

**Before tagging anything you are not certain about, look it up on the wiki.** Not
taginfo first, not intuition, not what looks reasonable — the wiki. It is the place the
community documents what a tag means and how it is meant to be combined.

Direct entry points, in this order:

| Question | URL |
|---|---|
| What does this key mean? | `https://wiki.openstreetmap.org/wiki/Key:<key>` |
| Is this exact value right? | `https://wiki.openstreetmap.org/wiki/Tag:<key>=<value>` |
| What tag exists for X? | `https://wiki.openstreetmap.org/wiki/Map_Features` |
| Anything else | `https://wiki.openstreetmap.org/w/index.php?search=<terms>` |
| Is it actually in use? | `https://taginfo.openstreetmap.org/api/4/tag/stats?key=K&value=V` |

Use taginfo **after** the wiki, as a reality check: a scheme the wiki documents but
nobody uses, or a value with a few hundred uses worldwide and no wiki page, is a
mistake rather than a convention (`natural=proposed`, 105 uses — the documented form is
the lifecycle prefix `proposed:natural=water`).

Country pages matter too: national import rules and tagging conventions live under
`https://wiki.openstreetmap.org/wiki/<Country>` and its `/Import` and `/Tagging`
subpages. Follow those over your own judgement.

### The principles that bite in this work

Full list in `osm-wiki.md`. The ones that decide real cases here:

- **Verifiability** — another mapper must be able to come to the same place and collect
  the same data. This is why a building that exists only as a granted permit does not
  belong in OSM: there is nothing on the ground to verify. It also rules out subjective
  or statistical values.
- **Map what is on the ground** — the register is evidence, the ground is the truth.
  When the register and the aerial disagree, say so rather than trusting the
  register blindly.
- **Don't map temporary or speculative features** — planned infrastructure without
  confirmed construction stays out.
- **Keep the history** — modify existing elements instead of deleting and redrawing.
  This is the reason for the 0.5 m re-import threshold: rewriting a correct outline
  churns node history for nothing. Prefer `replace_geometry` over delete-and-create.
- **One feature, one element** — no duplicate way on top of a coastline, no second node
  for the same thing.
- **Don't remove tags or objects you don't understand.** If a tag is unfamiliar, look it
  up or leave it and report it; do not tidy it away.
- **Don't map for the renderer or the router** — never distort geometry or tags to make
  something display or route the way you want.
- **Document custom tags** — the flip side of never inventing one silently.
- **Good changeset comments** — `pending_changes_summary` gives you the counts, bbox and
  undo history to write one.

### Practical tagging rules that follow

- **Match the surrounding data.** Copy the tag set already used nearby for the same kind
  of feature rather than inventing a variant.
- **Lifecycle prefixes**: `proposed:*`, `construction:*`, `disused:*`, `abandoned:*`,
  `demolished:*`. `highway=proposed` + `proposed=unclassified` is right for a planned
  road; `building=construction` + `construction=residential` for one actually being
  built. `construction=yes` adds nothing — drop it.
- `wikipedia` must be the live title, not a redirect, and must agree with `wikidata`.
  Resolve both before editing via
  `https://<lang>.wikipedia.org/w/api.php?action=query&titles=X&redirects=1&prop=pageprops&format=json`.
  A "wikidata does not match" warning is often *only* the redirect — verify before
  touching `wikidata`.

## 8. Verify against the source of truth, not against OSM's own tags

`building=construction`, `source:date` and `start_date` in OSM are claims, often stale.
Go to the register. When a decision depends on external state: fetch it, index it by a
stable id, and cross-check with a second independent method. **Two methods agreeing on
the same number is what makes a bulk edit defensible.**

## 9. Validator triage

Run `validate`, but never report its output raw. Sort into:

- **Real defects** — fix or report.
- **Known noise** — recurring findings with a structural cause. State the cause once
  instead of listing them. Two examples, both common wherever islands and water are
  mapped in detail:
  - *"Multipolygon contains a member that is not a way"* — grouping relations such as
    an archipelago legitimately take other relations as members.
  - *"Water area inside water area"* — JOSM's MapCSS test does not subtract inner
    rings, so everything inside a correctly-cut-out island is flagged against the
    water body around it.
- **Claims you have not checked.** Never call a finding a false positive on a hunch.
  Prove it — read the relation roles, point-in-polygon every flagged feature against the
  ring — and give the numbers.

Scope `changes` also pulls in parent ways of moved nodes. Use `tests` to narrow,
`max_findings` to cap (the summary still covers all), `output_path` when it is long.
**PT_Assistant and other plugins' upload-hook checks are not reachable** — tell the
mapper to run JOSM's own upload check for public-transport routes.

## 10. Editing

- Compute geometry yourself where no tool exists (orthogonalising, centroids, offsets)
  and apply with `update_nodes`. These set **exact** coordinates — do not reintroduce
  floating-point noise, it defeats duplicate-node detection.
- Prefer the non-destructive option. To connect a loose way end to a nearby way, prepend
  the junction node with `insert_node_in_way` (`index: 0`) rather than dragging the
  endpoint — that connects without moving existing geometry.
- `replace_geometry` and `reshape_area` have real failure modes (dropped shared nodes,
  gluing). Read `josmmcp-tools.md` before using them; `reshape_area` has a
  `dry_run`, always use it first.
- `delete_elements` with `delete_way_nodes: false` when the nodes are shared.
- Afterwards: `validate` with `scope: "changes"`, then `pending_changes_summary` so the
  mapper knows exactly what is queued and can write a changeset comment.

## 11. Undo and recovery

`undo`/`redo` **refuse commands the mapper made themselves** unless `force=true`. Never
force over their work. `list_commands` shows the stack with `by_plugin` per entry, so
check there before undoing anything. All plugin commands are marked "(MCP)".

Before anything risky, `save_layer` preserves pending edits across a restart, and
`save_session` keeps the whole layer and imagery set-up.

## 12. Reporting

State what you measured and how. Give ids so the mapper can select them. Separate
"fixed", "found but not touched" and "noise". Say plainly that nothing is uploaded.
