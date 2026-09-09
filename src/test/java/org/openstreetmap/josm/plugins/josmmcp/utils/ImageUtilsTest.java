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
package org.openstreetmap.josm.plugins.josmmcp.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class ImageUtilsTest {

	@Test
	void limitWidthKeepsAspectRatioAndSkipsSmallImages() {
		BufferedImage big = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_ARGB);
		BufferedImage scaled = ImageUtils.limitWidth(big, 500);
		assertEquals(500, scaled.getWidth());
		assertEquals(250, scaled.getHeight());

		BufferedImage small = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
		assertSame(small, ImageUtils.limitWidth(small, 500));
	}

	@Test
	void encodesPngAndJpegThatDecodeAgain() throws IOException {
		BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
		img.getGraphics().fillRect(0, 0, 32, 32);

		byte[] png = ImageUtils.encode(img, "png", 0.8f);
		BufferedImage decodedPng = ImageIO.read(new ByteArrayInputStream(png));
		assertEquals(64, decodedPng.getWidth());
		assertEquals("image/png", ImageUtils.mimeType("png"));

		byte[] jpeg = ImageUtils.encode(img, "jpeg", 0.8f);
		BufferedImage decodedJpeg = ImageIO.read(new ByteArrayInputStream(jpeg));
		assertEquals(32, decodedJpeg.getHeight());
		assertTrue(jpeg.length > 0);
		assertEquals("image/jpeg", ImageUtils.mimeType("jpeg"));
		assertEquals("image/jpeg", ImageUtils.mimeType(null));
	}

	@Test
	void rejectsUnknownFormat() {
		BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
		assertThrows(IOException.class, () -> ImageUtils.encode(img, "gif", 1f));
	}
}
