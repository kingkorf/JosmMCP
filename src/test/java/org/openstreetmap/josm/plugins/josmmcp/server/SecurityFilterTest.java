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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecurityFilterTest {

	@Test
	void hostNameStripsPortAndBrackets() {
		assertEquals("localhost", SecurityFilter.hostName("localhost:3000"));
		assertEquals("127.0.0.1", SecurityFilter.hostName("127.0.0.1"));
		assertEquals("::1", SecurityFilter.hostName("[::1]:3000"));
		assertEquals("evil.example", SecurityFilter.hostName("EVIL.example:80"));
		assertNull(SecurityFilter.hostName(""));
		assertNull(SecurityFilter.hostName(null));
	}

	@Test
	void onlyLoopbackOriginsAreAllowed() {
		assertTrue(SecurityFilter.isLoopbackOrigin("http://localhost"));
		assertTrue(SecurityFilter.isLoopbackOrigin("http://127.0.0.1:8080"));
		assertTrue(SecurityFilter.isLoopbackOrigin("http://[::1]:3000"));
		assertFalse(SecurityFilter.isLoopbackOrigin("https://evil.example"));
		assertFalse(SecurityFilter.isLoopbackOrigin("null"));
		assertFalse(SecurityFilter.isLoopbackOrigin("not a url"));
	}

	@Test
	void hostAllowListFollowsBindAddress() {
		SecurityFilter loopback = new SecurityFilter("127.0.0.1", () -> "");
		assertTrue(loopback.isAllowedHost("localhost"));
		assertFalse(loopback.isAllowedHost("192.168.1.10"));
		SecurityFilter lan = new SecurityFilter("192.168.1.10", () -> "");
		assertTrue(lan.isAllowedHost("192.168.1.10"));
		assertTrue(lan.isAllowedHost("localhost"));
		assertFalse(lan.isAllowedHost("evil.example"));
		SecurityFilter any = new SecurityFilter("0.0.0.0", () -> "");
		assertFalse(any.isAllowedHost("evil.example"));
	}

	@Test
	void constantTimeEquals() {
		assertTrue(SecurityFilter.constantTimeEquals("abc", "abc"));
		assertFalse(SecurityFilter.constantTimeEquals("abc", "abd"));
		assertFalse(SecurityFilter.constantTimeEquals("abc", ""));
	}
}
