/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/** HTTP calls of a flow: any API ({@code http}), the MockMDS admin API ({@code mds}) and the face source ({@code source}). */
final class FlowHttp {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	private static final String MDS = System.getProperty("mds.url", "http://127.0.0.1:4501");
	/** Frame pack root; the default is the run layout's {@code run/frame-packs} next to the client folder. */
	static final Path PACKS = Path.of(System.getProperty("frame.packs", "../frame-packs")).toAbsolutePath()
			.normalize();

	private final FlowContext c;

	FlowHttp(FlowContext c) {
		this.c = c;
	}

	void source(String pack) throws Exception {
		String path = PACKS.resolve(pack).toString().replace('\\', '/');
		mds("/admin/source",
				"{\"type\":\"Face\",\"source\":\"SEQUENCE\",\"path\":\"" + path + "\",\"fps\":15,\"loop\":true}");
		c.currentSource = pack;
	}

	/** Calls an HTTP API (for example the MockSync admin API) and logs the status and the start of the answer. */
	void http(String method, String url, String body) throws Exception {
		HttpRequest.BodyPublisher publisher = body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(body);
		HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json").method(method.toUpperCase(Locale.ROOT), publisher).build(),
				HttpResponse.BodyHandlers.ofString());
		String answer = r.body().replaceAll("\\s+", " ");
		c.logLine("  HTTP " + method + " " + url + " -> " + r.statusCode() + " "
				+ answer.substring(0, Math.min(160, answer.length())));
	}

	void mds(String path, String body) throws Exception {
		HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(MDS + path)).timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		String answer = r.body().replaceAll("\\s+", " ");
		c.logLine("  MDS " + path + " -> " + answer.substring(0, Math.min(160, answer.length())));
	}
}
