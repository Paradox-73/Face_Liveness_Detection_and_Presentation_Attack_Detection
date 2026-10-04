/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import com.github.jaiimageio.jpeg2000.J2KImageWriteParam;

import io.mosip.biometrics.util.ConvertRequestDto;
import io.mosip.biometrics.util.face.FaceDecoder;
import io.mosip.mock.sbi.SBIConstant;
import io.mosip.mock.sbi.util.BioUtilHelper;

/**
 * Turns a stream frame into the ISO/IEC 19794-5:2011 face record that
 * RCAPTURE signs: JPEG, then JPEG 2000 in memory, then the ISO record written
 * by MOSIP biometrics-util ({@link BioUtilHelper#getFaceIsoFromJP2000}).
 *
 * <p>
 * The JPEG 2000 settings are those of the stock file-based
 * {@code ImageHelper.toJ2000}: lossy, 9/7 wavelet, 2 bits per pixel.
 * </p>
 */
public final class FaceIsoBuilder {
	private static final float ENCODING_RATE_BITS_PER_PIXEL = 2.0f;

	private FaceIsoBuilder() {
		throw new IllegalStateException("FaceIsoBuilder class");
	}

	/**
	 * Builds the face ISO record from a JPEG frame.
	 *
	 * @param jpeg    the frame
	 * @param purpose device purpose, Registration or Auth
	 * @return the ISO 19794-5:2011 record
	 * @throws Exception if the JPEG cannot be decoded or the record written
	 */
	@SuppressWarnings({ "java:S112" })
	public static byte[] buildFaceIso(byte[] jpeg, String purpose) throws Exception {
		return BioUtilHelper.getFaceIsoFromJP2000(purpose, SBIConstant.MOSIP_BIOMETRIC_SUBTYPE_FACE, toJp2(jpeg));
	}

	/**
	 * Converts a JPEG to JPEG 2000 in memory.
	 *
	 * @param jpeg the JPEG bytes
	 * @return JPEG 2000 (JP2 file format) bytes
	 * @throws IOException if the JPEG cannot be decoded or no JPEG 2000 writer
	 *                     is available
	 */
	public static byte[] toJp2(byte[] jpeg) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
		if (image == null)
			throw new IOException("frame is not a decodable image");
		Iterator<ImageWriter> writers = ImageIO.getImageWritersBySuffix("jp2");
		if (!writers.hasNext())
			throw new IOException("no JPEG 2000 image writer on the class path");
		ImageWriter writer = writers.next();
		try {
			J2KImageWriteParam param = (J2KImageWriteParam) writer.getDefaultWriteParam();
			param.setLossless(false);
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionType("JPEG2000");
			param.setCompressionQuality(0.90f);
			param.setEncodingRate(ENCODING_RATE_BITS_PER_PIXEL);
			param.setFilter(J2KImageWriteParam.FILTER_97);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
				writer.setOutput(ios);
				writer.write(null, new IIOImage(image, null, null), param);
			}
			return out.toByteArray();
		} finally {
			writer.dispose();
		}
	}

	/**
	 * Decodes the image inside a face ISO record, for checks and tests.
	 *
	 * @param iso     the ISO 19794-5:2011 record
	 * @param purpose device purpose, Registration or Auth
	 * @return the decoded image
	 * @throws Exception if the record cannot be decoded
	 */
	@SuppressWarnings({ "java:S112" })
	public static BufferedImage decodeFaceIso(byte[] iso, String purpose) throws Exception {
		ConvertRequestDto request = new ConvertRequestDto();
		request.setModality("Face");
		request.setPurpose(purpose);
		request.setVersion("ISO19794_5_2011");
		request.setInputBytes(iso);
		return FaceDecoder.convertFaceISOToBufferedImage(request);
	}
}
