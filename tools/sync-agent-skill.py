#!/usr/bin/env python3
"""Regenerates .claude/skills/osm-mapping/SKILL.md from docs/agent-guide/README.md.

Run from the repository root. AgentGuideTest fails the build when the two drift apart.
"""
import pathlib
import sys

GUIDE = pathlib.Path("docs/agent-guide/README.md")
SKILL = pathlib.Path(".claude/skills/osm-mapping/SKILL.md")

FRONTMATTER = """---
name: osm-mapping
description: Edit OpenStreetMap data in a live JOSM through the JosmMCP tools, following OSM wiki tagging conventions and verifying against authoritative registers. Use for OSM surveying, validating, tagging, register or import checking, geometry repair or any mapping work driven from JOSM.
---

"""

# Links are sibling-relative in the guide and repo-root-relative in the skill.
REWRITES = [
    ("josmmcp-tools.md", "docs/agent-guide/josmmcp-tools.md"),
    ("osm-wiki.md", "docs/agent-guide/osm-wiki.md"),
    ("dutch-sources.md", "docs/agent-guide/dutch-sources.md"),
    ("german-sources.md", "docs/agent-guide/german-sources.md"),
    ("../../README.md", "README.md"),
]


def to_skill(guide_text):
    out = guide_text
    for src, dst in REWRITES:
        out = out.replace(src, dst)
    return FRONTMATTER + out


if __name__ == "__main__":
    want = to_skill(GUIDE.read_text())
    if "--check" in sys.argv:
        sys.exit(0 if SKILL.read_text() == want else "SKILL.md is out of sync with the guide")
    SKILL.parent.mkdir(parents=True, exist_ok=True)
    SKILL.write_text(want)
    print(f"wrote {SKILL} ({len(want)} chars)")
