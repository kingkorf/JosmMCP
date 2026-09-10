/*
 * JosmMCPPlugin - JOSM plugin to integrate JOSM with the Model Context Protocol
 * Copyright (C) 2025-2026 Pengunaria.dev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.openstreetmap.josm.plugins.josmmcp.tools;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.actions.MergeNodesAction;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.TagCollection;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Merges groups of nodes into one node each, like JOSM's Merge Nodes (M), for many groups in one undo
 * step. Groups that would need the mapper to resolve tag or relation conflicts are refused up front, so the
 * tool never opens a dialog.
 */
public class MergeNodes extends BaseTool {

	static final int MAX_GROUPS = 2000;

	@Override
	public String getName() {
		return "merge_nodes";
	}

	@Override
	public String getDescription() {
		return "Merge groups of nodes into one node each, like JOSM's Merge Nodes: parent ways and relations are "
				+ "re-pointed to the surviving node, tags are combined, the others are deleted. Takes many groups at once "
				+ "as one undo step. The survivor is the first id of a group when keep_first=true, otherwise JOSM's choice "
				+ "(an existing node over a new one); its position is kept. Groups whose nodes carry different values "
				+ "for the same key, or in which several nodes are relation members, are refused (nothing is merged) "
				+ "unless skip_conflicts=true, which merges the others and reports the skipped ones. Use "
				+ "find_duplicate_nodes to find candidates.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("groups", Map.of("type", "array", "minItems", 1, "maxItems", MAX_GROUPS,
				"description", "Groups of node ids; each group becomes one node",
				"items", Map.of("type", "array", "items", Map.of("type", "integer"), "minItems", 2)));
		props.put("keep_first", Map.of("type", "boolean",
				"description", "Keep the first node of each group (default false: JOSM picks, preferring an existing node)"));
		props.put("skip_conflicts", Map.of("type", "boolean",
				"description", "Merge the conflict-free groups and report the others instead of refusing everything (default false)"));
		props.put("description", Map.of("type", "string", "description", "Short text for the undo history"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("groups"), null, null, null);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}

	/**
	 * Why a group cannot be merged without asking the mapper, or null when it can: different values for
	 * one key, or more than one node that is a relation member (roles would have to be resolved).
	 */
	static String conflict(List<Node> group) {
		TagCollection tags = TagCollection.unionOfAllPrimitives(group);
		Set<String> multi = tags.getKeysWithMultipleValues();
		if (!multi.isEmpty()) {
			return "different values for " + multi;
		}
		int inRelations = 0;
		for (Node n : group) {
			for (OsmPrimitive p : n.getReferrers()) {
				if (p instanceof Relation) {
					inRelations++;
					break;
				}
			}
		}
		if (inRelations > 1) {
			return inRelations + " nodes are relation members";
		}
		return null;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		Object groupsObj = requireArg(args, "groups");
		if (!(groupsObj instanceof List) || ((List<?>) groupsObj).isEmpty() || ((List<?>) groupsObj).size() > MAX_GROUPS) {
			throw new Exception("groups must be an array of 1 to " + MAX_GROUPS + " arrays of node ids");
		}
		boolean keepFirst = Boolean.TRUE.equals(args.get("keep_first"));
		boolean skipConflicts = Boolean.TRUE.equals(args.get("skip_conflicts"));

		Set<Node> seen = new LinkedHashSet<>();
		List<List<Node>> groups = new ArrayList<>();
		for (Object g : (List<?>) groupsObj) {
			if (!(g instanceof List) || ((List<?>) g).size() < 2) {
				throw new Exception("each group must be an array of at least 2 node ids");
			}
			List<Node> group = new ArrayList<>();
			for (Object idObj : (List<?>) g) {
				long id = toLong(idObj, "groups");
				Node n = (Node) ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.NODE));
				if (n == null || n.isDeleted() || n.isIncomplete()) {
					throw new Exception("node " + id + " not found; nothing was merged");
				}
				if (!seen.add(n)) {
					throw new Exception("node " + id + " appears in more than one group; nothing was merged");
				}
				group.add(n);
			}
			groups.add(group);
		}

		List<Command> cmds = new ArrayList<>();
		List<Map<String, Object>> merged = new ArrayList<>();
		List<Map<String, Object>> skipped = new ArrayList<>();
		int deleted = 0;
		for (List<Node> group : groups) {
			String why = conflict(group);
			if (why != null) {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("nodes", ids(group));
				s.put("conflict", why);
				if (!skipConflicts) {
					undoAll(cmds);
					throw new Exception("group " + ids(group) + " cannot be merged without the mapper: " + why
							+ "; nothing was merged (set skip_conflicts=true to merge the other groups)");
				}
				skipped.add(s);
				continue;
			}
			Node target = keepFirst ? group.get(0) : MergeNodesAction.selectTargetNode(group);
			Command c = MergeNodesAction.mergeNodes(group, target, target);
			if (c == null) {
				undoAll(cmds);
				throw new Exception("group " + ids(group) + " could not be merged; nothing was merged");
			}
			// Each merge command is computed against the current state, so a way that loses nodes to
			// several groups is seen with the earlier merges applied. Execute now, undo all before handing
			// the sequence to the undo stack, which executes them again in the same order.
			c.executeCommand();
			cmds.add(c);
			deleted += group.size() - 1;
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("survivor", target.getUniqueId());
			m.put("merged", ids(group));
			merged.add(m);
		}
		if (!cmds.isEmpty()) {
			undoAll(cmds);
			Object d = args.get("description");
			String description = (d != null && !d.toString().isBlank() ? d.toString() : tr("Merge {0} groups of nodes", cmds.size()))
					+ " " + COMMAND_MARKER;
			addCommand(new SequenceCommand(description, cmds), description);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("groups_merged", merged.size());
		result.put("nodes_removed", deleted);
		result.put("groups_skipped", skipped.size());
		result.put("merged", merged);
		if (!skipped.isEmpty()) {
			result.put("skipped", skipped);
		}
		return JosmUtils.toJson(result);
	}

	private static void undoAll(List<Command> cmds) {
		for (int i = cmds.size() - 1; i >= 0; i--) {
			cmds.get(i).undoCommand();
		}
	}

	private static List<Long> ids(List<Node> group) {
		List<Long> ids = new ArrayList<>();
		for (Node n : group) {
			ids.add(n.getUniqueId());
		}
		return ids;
	}

	@Override
	public Category category() {
		return Category.GEOMETRY;
	}
}
