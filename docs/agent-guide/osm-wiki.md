# The OSM wiki: lookup and principles

The wiki is the primary source for what a tag means. Consult it **before** tagging
anything you are not certain about, and prefer it over inference from surrounding data,
over taginfo counts, and over your own sense of what is reasonable.

## Lookup order

1. `https://wiki.openstreetmap.org/wiki/Key:<key>` — the key's meaning, its values, and
   which of them are approved, in use, or deprecated.
2. `https://wiki.openstreetmap.org/wiki/Tag:<key>=<value>` — the specific combination.
3. `https://wiki.openstreetmap.org/wiki/Map_Features` — to find which tag covers a
   feature you can describe but cannot name.
4. `https://wiki.openstreetmap.org/w/index.php?search=<terms>` — free search for
   everything else, including proposals and discussion.
5. Country and region pages for national conventions and import rules, e.g.
   `https://wiki.openstreetmap.org/wiki/<Country>` and its `/Import` and `/Tagging`
   subpages (for example `.../wiki/The_Netherlands`).
   **National import rules override your own judgement.**
6. `https://taginfo.openstreetmap.org/api/4/tag/stats?key=K&value=V` — a reality check
   *after* the wiki, not instead of it.

A tag that has a wiki page and real usage is safe. A tag with neither is a mistake.
A tag with usage but no page is undocumented — check whether a documented equivalent
exists before copying it.

## The good-practice principles

From `https://wiki.openstreetmap.org/wiki/Good_practice`:

1. **Do correct errors** — edit boldly to reflect current reality.
2. **Verifiability** — another mapper must be able to visit and collect the same data.
3. **Map what's on the ground** — on-site evidence and local signs come first.
4. **Don't map historic events or features** — only what exists now.
5. **Don't map local legislation** unless it is bound to a specific object.
6. **Don't map temporary features** — map what will hold for weeks or months.
7. **Don't map for the renderer** — enter accurate data regardless of display.
8. **Don't use `name` to describe things** — `name` is for actual names.
9. **Don't map for the router.**
10. **Don't copy from other maps** — no copyrighted sources.
11. **Good changeset comments.**
12. **Keep the history** — modify existing elements rather than delete and redraw.
13. **One feature, one OSM element.**
14. **Editing standards** — imagery alignment, GPS traces, geometric accuracy.
15. **Document your custom tags.**
16. **Don't remove tags you don't understand.**
17. **Don't remove objects you don't need or like.**

## Verifiability in detail

From `https://wiki.openstreetmap.org/wiki/Verifiability`: data must be objectively
demonstrable as true or false by independent observation. It rules out

- **subjective opinions** — "delicious", "dangerous";
- **statistical properties** needing long-term observation — cars per hour;
- **vague descriptors** — `height=tall`, `waterway=big`;
- **unverifiable geometries** — the edge of a scattered settlement;
- **speculative features** — planned infrastructure without a confirmed route or
  imminent construction.

That last point is the principled reason a building whose only status in a register is a
granted permit does not belong in OSM: there is nothing on the ground for the next mapper to
check. It is also why `check_date` matters on fast-changing coastlines — it records when
the claim was last verifiable.

## Applying this to register-driven work

A national register — a building, address or cadastral database — is a **source**, not
the ground truth. Check first that its licence actually permits OSM use in that country,
which is what principle 10 demands; many do not. Once it does, principles 2 and 3 still bind:
when the register and the imagery disagree, report the disagreement instead of importing
the register's claim. And principle 12 is why you measure the deviation before
re-importing geometry rather than refreshing a whole import lichting.
