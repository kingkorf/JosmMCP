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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.actions.corrector.ReverseWayTagCorrector;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Reverses the node order of ways, in one undo step.
 *
 * <p>Ways whose meaning depends on their direction (oneway, incline, the :left/:right and
 * :forward/:backward suffixes, ...) are refused rather than silently turned into wrong data;
 * JOSM's own Reverse Way action offers the matching tag corrections in a dialog.
 */
public class ReverseWay extends BaseTool {
	private static final ObjectMapper JSON = new ObjectMapper();

	@Override
	public String getName() {
		return "reverse_way";
	}

	@Override
	public String getDescription() {
		return "Reverse the node order of one or more ways as a single undo step, for instance to make a waterway "
				+ "flow the other way. Tags are not touched: a way whose tags depend on its direction (oneway, "
				+ "incline, direction, the :left/:right and :forward/:backward suffixes) is refused, because "
				+ "reversing it without swapping those tags would make the data wrong - use JOSM's own Reverse Way "
				+ "for those, it offers the corrections. With skip_irreversible=true the other ways are reversed and "
				+ "the refused ones are reported with their tags.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("ids", Map.of("type", "array", "items", Map.of("type", "integer"), "minItems", 1, "maxItems", 500,
				"description", "Ids of the ways to reverse"));
		props.put("skip_irreversible", Map.of("type", "boolean", "description",
				"Reverse the ways without direction-dependent tags and report the others, instead of refusing "
						+ "everything (default false)"));
		return new McpSchema.JsonSchema("object", props, Collections.singletonList("ids"), null, null, null);
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
		Object idsObj = requireArg(args, "ids");
		if (!(idsObj instanceof List) || ((List<?>) idsObj).isEmpty()) {
			throw new Exception("ids must be a non-empty array of way ids");
		}
		boolean skipIrreversible = Boolean.TRUE.equals(args.get("skip_irreversible"));

		List<Way> ways = new ArrayList<>();
		for (Object o : (List<?>) idsObj) {
			long id = toLong(o, "ids");
			Way w = (Way) ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.WAY));
			if (w == null) {
				throw new Exception("Way with id " + id + " not found");
			}
			if (w.isIncomplete()) {
				throw new Exception("Way " + id + " is not fully downloaded");
			}
			if (w.getNodesCount() < 2) {
				throw new Exception("Way " + id + " has fewer than two nodes");
			}
			if (!ways.contains(w)) {
				ways.add(w);
			}
		}

		List<Way> reversible = new ArrayList<>();
		List<Map<String, Object>> skipped = new ArrayList<>();
		for (Way w : ways) {
			if (ReverseWayTagCorrector.isReversible(w)) {
				reversible.add(w);
			} else {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("id", w.getUniqueId());
				s.put("tags", w.getKeys());
				skipped.add(s);
			}
		}
		if (!skipped.isEmpty() && !skipIrreversible) {
			List<String> ids = new ArrayList<>();
			for (Map<String, Object> s : skipped) {
				ids.add(String.valueOf(s.get("id")));
			}
			throw new Exception("these ways have tags that depend on the direction and were not reversed: "
					+ String.join(", ", ids) + ". Reverse them with JOSM's Reverse Way action, which offers the tag "
					+ "corrections, or pass skip_irreversible=true to reverse the rest.");
		}
		if (reversible.isEmpty()) {
			throw new Exception("none of the given ways can be reversed without correcting direction-dependent tags");
		}

		List<Command> commands = new ArrayList<>();
		for (Way w : reversible) {
			List<Node> reversed = new ArrayList<>(w.getNodes());
			Collections.reverse(reversed);
			commands.add(new ChangeNodesCommand(ds, w, reversed));
		}
		String description = reversible.size() == 1
				? tr("Reverse way {0}", reversible.get(0).getUniqueId())
				: tr("Reverse {0} ways", reversible.size());
		addCommand(new SequenceCommand(description + " " + COMMAND_MARKER, commands, false), description);

		Map<String, Object> result = new LinkedHashMap<>();
		List<Long> done = new ArrayList<>();
		for (Way w : reversible) {
			done.add(w.getUniqueId());
		}
		result.put("reversed", done);
		result.put("reversed_count", done.size());
		if (!skipped.isEmpty()) {
			result.put("skipped_count", skipped.size());
			result.put("skipped", skipped);
			result.put("skipped_reason", "tags depend on the direction of the way");
		}
		return JSON.writeValueAsString(result);
	}

	@Override
	public Category category() {
		return Category.GEOMETRY;
	}
}
