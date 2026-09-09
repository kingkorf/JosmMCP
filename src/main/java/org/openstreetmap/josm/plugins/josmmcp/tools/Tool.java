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

import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;

public interface Tool {
	/** What a tool does, used for per-group permissions and MCP annotations. */
	enum Category {
		/** Reads data or renders it; never changes anything. */
		READ(false, "read"),
		/** Changes what the user sees (selection, view, layer visibility) but not the data. */
		VIEW(false, "view"),
		/** Adds, changes or removes tags. */
		TAGS(true, "tags"),
		/** Creates objects or changes geometry and relation membership. */
		GEOMETRY(true, "geometry"),
		/** Deletes objects. */
		DELETE(true, "delete"),
		/** Undo, redo, revert. */
		HISTORY(true, "history"),
		/** Reads or writes local files. */
		FILES(true, "files"),
		/** Downloads data or adds layers. */
		DOWNLOAD(true, "download");

		public final boolean write;
		public final String prefKey;

		Category(boolean write, String prefKey) {
			this.write = write;
			this.prefKey = prefKey;
		}
	}

	String getName();

	String getDescription();

	McpSchema.JsonSchema getInputSchema();

	String handle(Map<String, Object> args) throws Exception;

	default Category category() {
		return Category.READ;
	}

	/** True when the tool changes OSM data or files; such tools are blocked in read-only mode. */
	default boolean isWriteTool() {
		return category().write;
	}

	/** True when the tool deletes data or discards geometry (reported as destructiveHint, asks for confirmation). */
	default boolean isDestructive() {
		return category() == Category.DELETE;
	}

	/** True when the tool's text result is a JSON object (enables outputSchema and structured content). */
	default boolean returnsJson() {
		return false;
	}
}
