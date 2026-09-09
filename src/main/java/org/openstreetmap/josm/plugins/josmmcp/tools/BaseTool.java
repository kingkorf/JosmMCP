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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import javax.swing.SwingUtilities;

import org.openstreetmap.josm.tools.Logging;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import reactor.core.publisher.Mono;

public abstract class BaseTool implements org.openstreetmap.josm.plugins.josmmcp.tools.Tool {

	public McpStatelessServerFeatures.AsyncToolSpecification getSpec() {
		io.modelcontextprotocol.spec.McpSchema.Tool tool = Tool.builder().name(this.getName())
				.description(this.getDescription()).inputSchema(this.getInputSchema()).build();

		McpStatelessServerFeatures.AsyncToolSpecification spec = new McpStatelessServerFeatures.AsyncToolSpecification(
				tool, (exchange, params) -> {
					Logging.info(String.format("Tool '%s' called with params: %s", this.getName(), params.arguments()));
					try {
						Map<String, Object> args = params.arguments();
						List<Content> result = this.execute(args);
						Logging.debug(String.format("Returning '%s' result: %s", this.getName(), result));
						return Mono.just(CallToolResult.builder().content(result).isError(false).build());
					} catch (Exception e) {
						String message = e.getMessage() != null ? e.getMessage() : e.toString();
						Logging.error(String.format("Exception in '%s' - message: %s", this.getName(), message));
						Logging.debug(e);
						return Mono.just(CallToolResult.builder().content(Arrays.asList(new TextContent(message)))
								.isError(true).build());
					}
				});
		return spec;
	}

	/**
	 * Produces the tool result. By default runs {@link #handle(Map)} on the EDT and
	 * wraps its string in a single text content. Tools that return other content
	 * types (images) or need to leave the EDT between steps override this.
	 */
	protected List<Content> execute(Map<String, Object> args) throws Exception {
		String result = runInEDT(() -> this.handle(args));
		return Arrays.asList(new TextContent(result));
	}

	/**
	 * Runs the given task on the Swing Event Dispatch Thread and waits for the
	 * result. JOSM's DataSet, commands and UndoRedoHandler are not thread-safe
	 * and must only be touched from the EDT, while MCP requests arrive on Jetty
	 * worker threads.
	 */
	protected static <V> V runInEDT(java.util.concurrent.Callable<V> task) throws Exception {
		if (SwingUtilities.isEventDispatchThread()) {
			return task.call();
		}
		FutureTask<V> future = new FutureTask<>(task);
		SwingUtilities.invokeAndWait(future);
		try {
			return future.get();
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception) {
				throw (Exception) cause;
			}
			throw new Exception(cause);
		}
	}

	// --- argument helpers -------------------------------------------------

	/**
	 * Returns a required argument, or throws when it is missing.
	 */
	protected static Object requireArg(Map<String, Object> args, String key) throws Exception {
		Object value = args == null ? null : args.get(key);
		if (value == null) {
			throw new Exception("missing required argument '" + key + "'");
		}
		return value;
	}

	/**
	 * Converts a JSON number (Integer, Long, Double, BigDecimal or numeric
	 * String, depending on the deserializer) to a double.
	 */
	protected static double toDouble(Object value, String key) throws Exception {
		if (value instanceof Number) {
			return ((Number) value).doubleValue();
		}
		if (value instanceof String) {
			try {
				return Double.parseDouble((String) value);
			} catch (NumberFormatException e) {
				// fall through
			}
		}
		throw new Exception("argument '" + key + "' must be a number");
	}

	/**
	 * Converts a JSON number to a long, rejecting fractional values.
	 */
	protected static long toLong(Object value, String key) throws Exception {
		if (value instanceof Number) {
			double d = ((Number) value).doubleValue();
			if (d != Math.rint(d)) {
				throw new Exception("argument '" + key + "' must be an integer");
			}
			return ((Number) value).longValue();
		}
		if (value instanceof String) {
			try {
				return Long.parseLong(((String) value).trim());
			} catch (NumberFormatException e) {
				// fall through
			}
		}
		throw new Exception("argument '" + key + "' must be an integer");
	}

	protected static double getDouble(Map<String, Object> args, String key) throws Exception {
		return toDouble(requireArg(args, key), key);
	}

	protected static long getLong(Map<String, Object> args, String key) throws Exception {
		return toLong(requireArg(args, key), key);
	}

	protected static int getInt(Map<String, Object> args, String key, int defaultValue) throws Exception {
		Object value = args == null ? null : args.get(key);
		if (value == null) {
			return defaultValue;
		}
		return Math.toIntExact(toLong(value, key));
	}
}
