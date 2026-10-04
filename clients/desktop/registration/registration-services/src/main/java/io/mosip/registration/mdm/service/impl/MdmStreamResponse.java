/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.mdm.service.impl;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.impl.client.CloseableHttpClient;

/**
 * Body of an SBI {@code STREAM} response whose {@link #close()} drops the connection.
 *
 * <p>Closing the plain entity stream of an endless (chunked) MJPEG response would try to read it to the end,
 * blocking until the device's stream timeout. This wrapper instead closes the response and the client, which
 * closes the socket at once; a read blocked on another thread then fails and the reader ends. Idempotent.
 */
public final class MdmStreamResponse extends FilterInputStream {

	private final CloseableHttpClient client;
	private final CloseableHttpResponse response;
	private final AtomicBoolean closed = new AtomicBoolean();

	/**
	 * Wraps a stream response.
	 *
	 * @param client   the client that made the request; closed with the stream
	 * @param response the response
	 * @param body     the response entity content
	 */
	public MdmStreamResponse(CloseableHttpClient client, CloseableHttpResponse response, InputStream body) {
		super(body);
		this.client = client;
		this.response = response;
	}

	@Override
	public void close() throws IOException {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		try {
			response.close();
		} finally {
			client.close();
		}
	}
}
