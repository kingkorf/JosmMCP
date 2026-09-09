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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Protects the MCP endpoint against browsers and other local processes:
 * <ul>
 * <li>the Host header must name the address the server is bound to (blocks DNS rebinding),</li>
 * <li>a browser-supplied Origin header must be a loopback origin,</li>
 * <li>when a token is configured, every request needs {@code Authorization: Bearer <token>}.</li>
 * </ul>
 */
public class SecurityFilter implements Filter {
	private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1");

	private final String bindHost;
	private final Supplier<String> tokenSupplier;

	/**
	 * @param bindHost address the server listens on; loopback addresses restrict the Host header to loopback names
	 * @param tokenSupplier returns the required token, or an empty string when none is required (read per request)
	 */
	public SecurityFilter(String bindHost, Supplier<String> tokenSupplier) {
		this.bindHost = bindHost == null ? "" : bindHost.toLowerCase(Locale.ROOT);
		this.tokenSupplier = tokenSupplier;
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest req = (HttpServletRequest) request;
		HttpServletResponse res = (HttpServletResponse) response;

		String host = hostName(req.getHeader("Host"));
		if (host == null) {
			res.sendError(HttpServletResponse.SC_BAD_REQUEST, "Host header required");
			return;
		}
		if (!isAllowedHost(host)) {
			res.sendError(HttpServletResponse.SC_FORBIDDEN, "Host header not allowed");
			return;
		}

		String origin = req.getHeader("Origin");
		if (origin != null && !isLoopbackOrigin(origin)) {
			res.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
			return;
		}

		String token = tokenSupplier.get();
		if (token != null && !token.isEmpty()) {
			String auth = req.getHeader("Authorization");
			String presented = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).trim() : "";
			if (!constantTimeEquals(token, presented)) {
				res.setHeader("WWW-Authenticate", "Bearer realm=\"JosmMCP\"");
				res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid token");
				return;
			}
		}
		chain.doFilter(request, response);
	}

	boolean isAllowedHost(String host) {
		if (LOOPBACK.contains(host)) {
			return true;
		}
		// Bound to a specific non-loopback address: accept that name/address as well.
		return !bindHost.isEmpty() && !"0.0.0.0".equals(bindHost) && !"::".equals(bindHost) && bindHost.equals(host);
	}

	static boolean isLoopbackOrigin(String origin) {
		if ("null".equalsIgnoreCase(origin)) {
			return false;
		}
		try {
			URI u = new URI(origin);
			String h = u.getHost();
			if (h == null) {
				return false;
			}
			return LOOPBACK.contains(h.toLowerCase(Locale.ROOT));
		} catch (Exception e) {
			return false;
		}
	}

	/** Strips the port from a Host header value; IPv6 literals keep their brackets stripped. */
	static String hostName(String hostHeader) {
		if (hostHeader == null || hostHeader.isBlank()) {
			return null;
		}
		String h = hostHeader.trim().toLowerCase(Locale.ROOT);
		if (h.startsWith("[")) {
			int end = h.indexOf(']');
			return end > 0 ? h.substring(1, end) : null;
		}
		int colon = h.indexOf(':');
		return colon >= 0 ? h.substring(0, colon) : h;
	}

	static boolean constantTimeEquals(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
}
