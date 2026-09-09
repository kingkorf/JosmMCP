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
package org.openstreetmap.josm.plugins.josmmcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.josmmcp.Prefs;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Starts the real Jetty/MCP stack on an ephemeral port and talks to it over HTTP.
 */
class ServerIntegrationTest {
	private static final ObjectMapper JSON = new ObjectMapper();
	private static Server server;
	private static int port;

	@BeforeAll
	static void start() throws Exception {
		Config.setPreferencesInstance(new MemoryPreferences());
		server = McpServerRunner.create("127.0.0.1", 0, ToolRegistry.all(), Prefs::token, () -> "{\"state\":true}",
				() -> "{\"count\":0}");
		server.start();
		port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
	}

	@AfterAll
	static void stop() throws Exception {
		server.stop();
	}

	@AfterEach
	void resetPrefs() {
		Config.getPref().put(Prefs.TOKEN, "");
		Config.getPref().putBoolean(Prefs.READ_ONLY, false);
		Config.getPref().putInt(Prefs.MAX_OUTPUT_CHARS, Prefs.DEFAULT_MAX_OUTPUT_CHARS);
	}

	@Test
	void truncatedJsonResultStillHasStructuredContent() throws Exception {
		Config.getPref().putInt(Prefs.MAX_OUTPUT_CHARS, 40);
		Response r = post(rpc("tools/call", "{\"name\":\"get_josm_state\",\"arguments\":{}}"), Map.of());
		assertEquals(200, r.status, r.body);
		JsonNode result = JSON.readTree(r.body).path("result");
		assertFalse(result.path("isError").asBoolean(), r.body);
		assertTrue(result.has("structuredContent"), r.body);
		assertTrue(result.path("structuredContent").path("truncated").asBoolean(), r.body);
		assertTrue(result.path("content").get(0).path("text").asText().contains("output truncated"));
	}

	private static final class Response {
		final int status;
		final String body;

		Response(int status, String body) {
			this.status = status;
			this.body = body;
		}
	}

	private static Response post(String json, Map<String, String> headers) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/mcp").openConnection();
		c.setRequestMethod("POST");
		c.setDoOutput(true);
		c.setRequestProperty("Content-Type", "application/json");
		c.setRequestProperty("Accept", "application/json, text/event-stream");
		headers.forEach(c::setRequestProperty);
		try (OutputStream out = c.getOutputStream()) {
			out.write(json.getBytes(StandardCharsets.UTF_8));
		}
		int status = c.getResponseCode();
		InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
		String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
		return new Response(status, body);
	}

	private static String rpc(String method, String params) {
		return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
	}

	@Test
	void listsAllToolsWithAnnotations() throws Exception {
		Response r = post(rpc("tools/list", "{}"), Map.of());
		assertEquals(200, r.status, r.body);
		JsonNode tools = JSON.readTree(r.body).path("result").path("tools");
		assertEquals(ToolRegistry.all().size(), tools.size());
		Set<String> names = new HashSet<>();
		for (JsonNode t : tools) {
			names.add(t.path("name").asText());
			assertTrue(t.has("annotations"), "annotations missing on " + t.path("name"));
			assertTrue(t.path("annotations").has("readOnlyHint"));
		}
		for (String expected : List.of("get_josm_state", "search_elements", "modify_tags_batch", "replace_geometry",
				"undo", "save_layer", "validate", "capture_map_view", "create_relation")) {
			assertTrue(names.contains(expected), "missing tool " + expected);
		}
		for (JsonNode t : tools) {
			String n = t.path("name").asText();
			boolean ro = t.path("annotations").path("readOnlyHint").asBoolean();
			if (n.startsWith("read_") || n.startsWith("get_") || n.equals("search_elements") || n.equals("validate")) {
				assertTrue(ro, n + " should be read-only");
			}
			if (n.startsWith("delete_") || n.startsWith("create_") || n.equals("modify_tags")) {
				assertFalse(ro, n + " should not be read-only");
			}
			if (n.startsWith("delete_")) {
				assertTrue(t.path("annotations").path("destructiveHint").asBoolean(), n + " should be destructive");
			}
		}
	}

	@Test
	void readOnlyModeBlocksWriteTools() throws Exception {
		Config.getPref().putBoolean(Prefs.READ_ONLY, true);
		Response r = post(rpc("tools/call", "{\"name\":\"create_node\",\"arguments\":{\"latitude\":52.0,\"longitude\":5.0}}"), Map.of());
		assertEquals(200, r.status);
		JsonNode result = JSON.readTree(r.body).path("result");
		assertTrue(result.path("isError").asBoolean());
		assertTrue(result.path("content").get(0).path("text").asText().contains("read-only"));
	}

	/** Sends a request over a raw socket, so restricted headers such as Host and Origin reach the server. */
	private static String rawStatusLine(String hostHeader, Map<String, String> headers) throws Exception {
		String body = rpc("tools/list", "{}");
		StringBuilder req = new StringBuilder("POST /mcp HTTP/1.1\r\nHost: " + hostHeader
				+ "\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\n");
		headers.forEach((k, v) -> req.append(k).append(": ").append(v).append("\r\n"));
		req.append("Content-Length: ").append(body.length()).append("\r\nConnection: close\r\n\r\n").append(body);
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.getOutputStream().write(req.toString().getBytes(StandardCharsets.UTF_8));
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
			String statusLine = in.readLine();
			assertNotNull(statusLine);
			return statusLine;
		}
	}

	@Test
	void foreignOriginIsRejected() throws Exception {
		assertTrue(rawStatusLine("127.0.0.1:" + port, Map.of("Origin", "https://evil.example")).contains(" 403 "));
		assertTrue(rawStatusLine("127.0.0.1:" + port, Map.of("Origin", "http://localhost:3000")).contains(" 200 "));
	}

	@Test
	void tokenIsEnforcedWhenConfigured() throws Exception {
		Config.getPref().put(Prefs.TOKEN, "s3cret");
		assertEquals(401, post(rpc("tools/list", "{}"), Map.of()).status);
		assertEquals(401, post(rpc("tools/list", "{}"), Map.of("Authorization", "Bearer wrong")).status);
		assertEquals(200, post(rpc("tools/list", "{}"), Map.of("Authorization", "Bearer s3cret")).status);
	}

	@Test
	void foreignHostHeaderIsRejected() throws Exception {
		assertTrue(rawStatusLine("evil.example", Map.of()).contains(" 403 "));
		assertTrue(rawStatusLine("localhost:" + port, Map.of()).contains(" 200 "));
	}

	@Test
	void exposesResourcesAndPrompts() throws Exception {
		JsonNode res = JSON.readTree(post(rpc("resources/list", "{}"), Map.of()).body).path("result").path("resources");
		Set<String> uris = new HashSet<>();
		res.forEach(n -> uris.add(n.path("uri").asText()));
		assertTrue(uris.contains("josm://state"));
		assertTrue(uris.contains("josm://selection"));

		JsonNode read = JSON.readTree(post(rpc("resources/read", "{\"uri\":\"josm://state\"}"), Map.of()).body);
		assertEquals("{\"state\":true}", read.path("result").path("contents").get(0).path("text").asText());

		JsonNode prompts = JSON.readTree(post(rpc("prompts/list", "{}"), Map.of()).body).path("result").path("prompts");
		assertEquals(4, prompts.size());
	}
}
