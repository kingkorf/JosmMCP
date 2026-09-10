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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.openstreetmap.josm.command.SplitWayCommand;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.Logging;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Splits a way at nodes of its own, like JOSM's Split Way. One part keeps the way's id, tags and
 * history; the others become new ways with the same tags, and parent relations are updated.
 *
 * <p>Needed whenever a part of a line gets its own tags: a culvert under a path, a bridge over a
 * ditch, a stretch with a different surface. Rebuilding that by hand with create_way and
 * update_way_nodes loses the history of every part and has to copy the tags over.
 */
public class SplitWay extends BaseTool {
	private static final ObjectMapper JSON = new ObjectMapper();

	@Override
	public String getName() {
		return "split_way";
	}

	@Override
	public String getDescription() {
		return "Split a way at one or more of its own nodes, like JOSM's Split Way. The part chosen by 'keep' "
				+ "(default the longest) keeps the way's id, tags and history; the other parts become new ways with "
				+ "the same tags, and parent relations are updated to contain all parts in the right order. The split "
				+ "nodes must be nodes of the way; for an open way they must not be its first or last node, and a "
				+ "closed way needs at least two of them. Warnings that JOSM would show in a dialog (uncertain "
				+ "relation member order, incomplete relations) are returned instead, so check them when the way is "
				+ "in a route or a multipolygon.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("id", Map.of("type", "integer", "description", "The way to split"));
		props.put("at_node_ids", Map.of("type", "array", "items", Map.of("type", "integer"), "minItems", 1,
				"description", "Nodes of the way to split at; each becomes the shared end of two parts"));
		props.put("keep", Map.of("type", "string", "enum", Arrays.asList("longest", "first"), "description",
				"Which part keeps the original way's id and history: 'longest' (default) or 'first'"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("id", "at_node_ids"), null, null, null);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		long id = getLong(args, "id");
		Way w = (Way) ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.WAY));
		if (w == null) {
			throw new Exception("Way with id " + id + " not found");
		}
		if (w.isIncomplete()) {
			throw new Exception("Way " + id + " is not fully downloaded");
		}
		Object atObj = requireArg(args, "at_node_ids");
		if (!(atObj instanceof List) || ((List<?>) atObj).isEmpty()) {
			throw new Exception("at_node_ids must be a non-empty array of node ids");
		}
		List<Node> atNodes = new ArrayList<>();
		for (Object o : (List<?>) atObj) {
			long nid = toLong(o, "at_node_ids");
			Node n = (Node) ds.getPrimitiveById(new SimplePrimitiveId(nid, OsmPrimitiveType.NODE));
			if (n == null) {
				throw new Exception("Node with id " + nid + " not found");
			}
			if (!w.containsNode(n)) {
				throw new Exception("Node " + nid + " is not a node of way " + id);
			}
			if (!w.isClosed() && (w.firstNode().equals(n) || w.lastNode().equals(n))) {
				throw new Exception("Node " + nid + " is an end node of way " + id + "; splitting there would "
						+ "produce only one part");
			}
			if (!atNodes.contains(n)) {
				atNodes.add(n);
			}
		}
		if (w.isClosed() && atNodes.size() < 2) {
			throw new Exception("Way " + id + " is closed; splitting it needs at least two split nodes");
		}

		List<List<Node>> chunks = SplitWayCommand.buildSplitChunks(w, atNodes);
		if (chunks == null || chunks.size() < 2) {
			throw new Exception("the given nodes do not split way " + id + " into more than one part");
		}
		SplitWayCommand.Strategy strategy = "first".equals(args.get("keep"))
				? SplitWayCommand.Strategy.keepFirstChunk()
				: SplitWayCommand.Strategy.keepLongestChunk();

		// Parent relations before the split, so the caller can check the ones whose member order matters.
		List<Map<String, Object>> relations = new ArrayList<>();
		for (OsmPrimitive p : w.getReferrers()) {
			if (p instanceof Relation) {
				Map<String, Object> rm = new LinkedHashMap<>();
				rm.put("id", p.getUniqueId());
				rm.put("type", ((Relation) p).get("type"));
				rm.put("name", p.get("name"));
				rm.put("incomplete_members", ((Relation) p).hasIncompleteMembers());
				relations.add(rm);
			}
		}

		// JOSM reports uncertain relation order and missing members through a notifier that normally
		// opens a dialog; collect the text instead so it reaches the caller.
		List<String> warnings = new ArrayList<>();
		Optional<SplitWayCommand> cmd;
		try {
			SplitWayCommand.setWarningNotifier(warnings::add);
			cmd = SplitWayCommand.splitWay(w, chunks, Collections.emptyList(), strategy,
					SplitWayCommand.WhenRelationOrderUncertain.SPLIT_ANYWAY);
		} finally {
			// There is no getter for the notifier that was set before, so put JOSM's own logging one back.
			SplitWayCommand.setWarningNotifier(Logging::warn);
		}
		if (!cmd.isPresent()) {
			throw new Exception("JOSM could not split way " + id
					+ (warnings.isEmpty() ? "" : ": " + String.join("; ", warnings)));
		}
		SplitWayCommand split = cmd.get();
		List<Way> newWays = new ArrayList<>(split.getNewWays());
		addCommand(split, tr("Split way {0}", w.getUniqueId()));

		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, Object> kept = new LinkedHashMap<>();
		kept.put("id", split.getOriginalWay().getUniqueId());
		kept.put("nodes", split.getOriginalWay().getNodesCount());
		result.put("kept_way", kept);
		List<Map<String, Object>> parts = new ArrayList<>();
		for (Way nw : newWays) {
			Map<String, Object> pm = new LinkedHashMap<>();
			pm.put("id", nw.getUniqueId());
			pm.put("nodes", nw.getNodesCount());
			parts.add(pm);
		}
		result.put("new_ways", parts);
		result.put("parts", newWays.size() + 1);
		result.put("split_at", atNodes.stream().map(OsmPrimitive::getUniqueId).collect(java.util.stream.Collectors.toList()));
		if (!relations.isEmpty()) {
			result.put("relations_affected", relations);
		}
		if (!warnings.isEmpty()) {
			result.put("warnings", warnings);
		}
		return JSON.writeValueAsString(result);
	}

	@Override
	public Category category() {
		return Category.GEOMETRY;
	}
}
