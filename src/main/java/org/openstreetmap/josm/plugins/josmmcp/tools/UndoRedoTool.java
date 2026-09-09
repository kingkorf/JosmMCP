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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Undo/redo of JOSM's command stack, plus a listing of it. One class, three tool instances.
 */
public class UndoRedoTool extends BaseTool {
	public enum Mode { UNDO, REDO, LIST }

	private final Mode mode;

	public UndoRedoTool(Mode mode) {
		this.mode = mode;
	}

	@Override
	public String getName() {
		switch (mode) {
		case UNDO: return "undo";
		case REDO: return "redo";
		default: return "list_commands";
		}
	}

	@Override
	public String getDescription() {
		switch (mode) {
		case UNDO: return "Undo the last n commands on JOSM's undo stack (default 1), exactly like Edit > Undo. Returns the remaining stack.";
		case REDO: return "Redo the last n undone commands (default 1), like Edit > Redo.";
		default: return "List JOSM's undo stack (most recent first) and redo stack, with the description of each command. Use it to see what a previous tool call changed before undoing.";
		}
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		if (mode == Mode.LIST) {
			props.put("limit", Map.of("type", "integer", "description", "Maximum number of commands to list per stack (default 20)"));
		} else {
			props.put("n", Map.of("type", "integer", "description", "Number of commands (default 1)"));
		}
		return new McpSchema.JsonSchema("object", props, null, null, null, null);
	}


	@Override
	public String handle(Map<String, Object> args) throws Exception {
		UndoRedoHandler h = UndoRedoHandler.getInstance();
		Map<String, Object> result = new LinkedHashMap<>();
		if (mode == Mode.UNDO || mode == Mode.REDO) {
			int n = getInt(args, "n", 1);
			if (n < 1) {
				throw new Exception("n must be at least 1");
			}
			List<Command> stack = mode == Mode.UNDO ? h.getUndoCommands() : h.getRedoCommands();
			if (stack.isEmpty()) {
				throw new Exception("nothing to " + getName());
			}
			int done = Math.min(n, stack.size());
			List<String> descriptions = new ArrayList<>();
			for (int i = 0; i < done; i++) {
				descriptions.add(stack.get(stack.size() - 1 - i).getDescriptionText());
			}
			if (mode == Mode.UNDO) {
				h.undo(done);
			} else {
				h.redo(done);
			}
			result.put(mode == Mode.UNDO ? "undone" : "redone", descriptions);
		}
		int limit = getInt(args, "limit", 20);
		result.put("undo_stack", describe(h.getUndoCommands(), limit));
		result.put("redo_stack", describe(h.getRedoCommands(), limit));
		return JosmUtils.toJson(result);
	}

	private static List<Map<String, Object>> describe(List<Command> stack, int limit) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = stack.size() - 1; i >= 0 && out.size() < limit; i--) {
			Command c = stack.get(i);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("position", stack.size() - i);
			m.put("description", c.getDescriptionText());
			m.put("affected", c.getParticipatingPrimitives().size());
			out.add(m);
		}
		return out;
	}

	@Override
	public Category category() {
		return mode == Mode.LIST ? Category.READ : Category.HISTORY;
	}

	@Override
	public boolean returnsJson() {
		return true;
	}
}
