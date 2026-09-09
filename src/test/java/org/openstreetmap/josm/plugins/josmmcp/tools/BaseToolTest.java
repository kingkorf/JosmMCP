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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BaseToolTest {

	@Test
	void toDoubleAcceptsAnyJsonNumberRepresentation() throws Exception {
		assertEquals(52.0, BaseTool.toDouble(52, "lat"));
		assertEquals(52.5, BaseTool.toDouble(52.5, "lat"));
		assertEquals(52.0, BaseTool.toDouble(52L, "lat"));
		assertEquals(4.9, BaseTool.toDouble(new BigDecimal("4.9"), "lon"));
		assertEquals(4.9, BaseTool.toDouble("4.9", "lon"));
	}

	@Test
	void toDoubleRejectsNonNumbers() {
		Exception e = assertThrows(Exception.class, () -> BaseTool.toDouble("abc", "lat"));
		assertEquals("argument 'lat' must be a number", e.getMessage());
		assertThrows(Exception.class, () -> BaseTool.toDouble(Boolean.TRUE, "lat"));
	}

	@Test
	void toLongAcceptsIntegralNumbers() throws Exception {
		assertEquals(123L, BaseTool.toLong(123, "id"));
		assertEquals(123L, BaseTool.toLong(123.0, "id"));
		assertEquals(-5L, BaseTool.toLong(-5L, "id"));
		assertEquals(42L, BaseTool.toLong(" 42 ", "id"));
	}

	@Test
	void toLongRejectsFractionsAndGarbage() {
		assertThrows(Exception.class, () -> BaseTool.toLong(1.5, "id"));
		assertThrows(Exception.class, () -> BaseTool.toLong("1.5", "id"));
		assertThrows(Exception.class, () -> BaseTool.toLong(null, "id"));
	}

	@Test
	void requireArgReportsMissingKey() {
		Map<String, Object> args = new HashMap<>();
		Exception e = assertThrows(Exception.class, () -> BaseTool.requireArg(args, "id"));
		assertEquals("missing required argument 'id'", e.getMessage());
		assertThrows(Exception.class, () -> BaseTool.requireArg(null, "id"));
	}

	@Test
	void getIntFallsBackToDefault() throws Exception {
		Map<String, Object> args = new HashMap<>();
		assertEquals(50, BaseTool.getInt(args, "max_results", 50));
		args.put("max_results", 10);
		assertEquals(10, BaseTool.getInt(args, "max_results", 50));
		args.put("max_results", 1L << 40);
		assertThrows(ArithmeticException.class, () -> BaseTool.getInt(args, "max_results", 50));
	}
}
