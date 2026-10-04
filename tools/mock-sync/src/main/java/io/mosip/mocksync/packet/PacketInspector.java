/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync.packet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.mocksync.crypto.ServerCrypto;

/**
 * Reads the face liveness evidence out of a registration packet the desktop client uploaded, the way the MOSIP
 * server side would see it.
 *
 * <p>The client encrypts the whole packet zip to MockSync's {@code REGISTRATION/<center>_<machine>} key
 * ({@link ServerCrypto#decryptPacketData}). Inside are the {@code id}, {@code evidence} and {@code optional}
 * sub-packets (zips, encrypted the same way when the packet manager is configured to). The inspector reports:
 * <ul>
 * <li>for every CBEFF file, each face record (BIR) and each record with {@code LIVENESS_*} entries in its
 * {@code others} map (resident face, officer and supervisor biometrics), with type and subtype; other records are
 * only counted;</li>
 * <li>the {@code livenessDecision} / {@code livenessMethod} fields of {@code packet_meta_info.json};</li>
 * <li>the {@code REG-EVT-LV*} rows of {@code audit.json}.</li>
 * </ul>
 * No biometric data, image or template leaves this class: only names, keys and the liveness values.
 */
public final class PacketInspector {

	private static final Pattern PACKET_NAME = Pattern.compile("^(\\d+)-([A-Za-z0-9]+_[A-Za-z0-9]+)-.*\\.zip$");
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private PacketInspector() {
	}

	/**
	 * Returns the {@code <center>_<machine>} reference of an uploaded packet from its file name
	 * ({@code <registrationId>-<center>_<machine>-<timestamp>.zip}).
	 *
	 * @param packetName file name
	 * @return the reference ID
	 * @throws IllegalArgumentException if the name has another form
	 */
	public static String referenceId(String packetName) {
		Matcher m = PACKET_NAME.matcher(packetName);
		if (!m.matches()) {
			throw new IllegalArgumentException("not a packet file name: " + packetName);
		}
		return m.group(2);
	}

	/**
	 * Decrypts and inspects one packet.
	 *
	 * @param packetName    file name (gives the registration ID)
	 * @param encrypted     packet bytes as uploaded
	 * @param registrationKey private key of the machine's REGISTRATION certificate
	 * @return report: {@code packet}, {@code registrationId}, {@code files}, {@code faceRecords}, {@code metaInfo},
	 *         {@code livenessAudits}, {@code otherBiometricRecords} (count)
	 * @throws GeneralSecurityException if the packet does not decrypt with the key
	 * @throws IOException              if the packet is not a readable zip
	 */
	public static Map<String, Object> inspect(String packetName, byte[] encrypted, PrivateKey registrationKey)
			throws GeneralSecurityException, IOException {
		Map<String, Object> report = new LinkedHashMap<>();
		Matcher m = PACKET_NAME.matcher(packetName);
		report.put("packet", packetName);
		report.put("registrationId", m.matches() ? m.group(1) : null);
		List<String> files = new ArrayList<>();
		List<Map<String, Object>> records = new ArrayList<>();
		List<Map<String, Object>> metaInfo = new ArrayList<>();
		List<Map<String, Object>> audits = new ArrayList<>();
		report.put("files", files);
		report.put("faceRecords", records);
		report.put("metaInfo", metaInfo);
		report.put("livenessAudits", audits);

		byte[] outer = ServerCrypto.decryptPacketData(registrationKey, encrypted);
		List<Map<String, Object>> allRecords = new ArrayList<>();
		readZip(outer, "", registrationKey, files, allRecords, metaInfo, audits);
		int otherRecords = 0;
		for (Map<String, Object> r : allRecords) {
			if ("Face".equalsIgnoreCase(String.valueOf(r.get("type"))) || !((Map<?, ?>) r.get("liveness")).isEmpty()) {
				records.add(r);
			} else {
				otherRecords++;
			}
		}
		report.put("otherBiometricRecords", otherRecords);
		return report;
	}

	private static void readZip(byte[] zip, String prefix, PrivateKey key, List<String> files,
			List<Map<String, Object>> records, List<Map<String, Object>> metaInfo, List<Map<String, Object>> audits)
			throws IOException, GeneralSecurityException {
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				String name = prefix + entry.getName();
				byte[] data = in.readAllBytes();
				files.add(name);
				String lower = entry.getName().toLowerCase(java.util.Locale.ROOT);
				if (lower.endsWith(".zip")) {
					readZip(isZip(data) ? data : ServerCrypto.decryptPacketData(key, data), name + "/", key, files, records,
							metaInfo, audits);
				} else if (lower.endsWith(".xml") && lower.contains("cbeff")) {
					records.addAll(birs(name, data));
				} else if (lower.equals("packet_meta_info.json") || lower.endsWith("/packet_meta_info.json")) {
					metaInfo.addAll(livenessFields(name, MAPPER.readTree(data)));
				} else if (lower.equals("audit.json") || lower.endsWith("/audit.json")) {
					audits.addAll(livenessAudits(MAPPER.readTree(data)));
				}
			}
		}
	}

	private static boolean isZip(byte[] data) {
		return data.length > 3 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4;
	}

	/** Every BIR that carries biometric data: type, subtype and its {@code LIVENESS_*} entries. */
	static List<Map<String, Object>> birs(String file, byte[] xml) throws IOException {
		Document doc = parse(xml);
		List<Map<String, Object>> out = new ArrayList<>();
		NodeList all = doc.getElementsByTagNameNS("*", "BIR");
		for (int i = 0; i < all.getLength(); i++) {
			Element bir = (Element) all.item(i);
			Element info = child(bir, "BDBInfo");
			if (info == null) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("file", file);
			row.put("type", text(child(info, "Type")));
			row.put("subtype", text(child(info, "Subtype")));
			Map<String, String> liveness = new LinkedHashMap<>();
			Element others = child(bir, "others");
			if (others != null) {
				for (Node n = others.getFirstChild(); n != null; n = n.getNextSibling()) {
					if (n instanceof Element && ((Element) n).getAttribute("key").startsWith("LIVENESS_")) {
						liveness.put(((Element) n).getAttribute("key"), n.getTextContent().trim());
					}
				}
			}
			row.put("liveness", liveness);
			out.add(row);
		}
		return out;
	}

	/** Every object of the meta info that has a liveness field, with its JSON path. */
	static List<Map<String, Object>> livenessFields(String file, JsonNode root) {
		List<Map<String, Object>> out = new ArrayList<>();
		collectLiveness(file, "", root, out);
		return out;
	}

	private static void collectLiveness(String file, String path, JsonNode node, List<Map<String, Object>> out) {
		if (node.isObject()) {
			if (node.has("livenessDecision") || node.has("livenessMethod")) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("file", file);
				row.put("path", path);
				row.put("livenessDecision", node.path("livenessDecision").asText(null));
				row.put("livenessMethod", node.path("livenessMethod").asText(null));
				out.add(row);
			}
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> f = fields.next();
				collectLiveness(file, path + "/" + f.getKey(), f.getValue(), out);
			}
		} else if (node.isArray()) {
			for (int i = 0; i < node.size(); i++) {
				collectLiveness(file, path + "/" + i, node.get(i), out);
			}
		}
	}

	/** The {@code REG-EVT-LV*} rows of {@code audit.json} (event, time, JSON description without images). */
	static List<Map<String, Object>> livenessAudits(JsonNode root) {
		List<Map<String, Object>> out = new ArrayList<>();
		JsonNode rows = root.isArray() ? root : root.path("audits");
		for (JsonNode row : rows) {
			String eventId = row.path("eventId").asText("");
			if (!eventId.startsWith("REG-EVT-LV")) {
				continue;
			}
			Map<String, Object> audit = new LinkedHashMap<>();
			audit.put("eventId", eventId);
			audit.put("eventName", row.path("eventName").asText(null));
			audit.put("actionTimeStamp", row.path("actionTimeStamp").asText(null));
			audit.put("sessionUserId", row.path("sessionUserId").asText(null));
			audit.put("description", row.path("description").asText(null));
			out.add(audit);
		}
		return out;
	}

	private static Document parse(byte[] xml) throws IOException {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			try (InputStream in = new ByteArrayInputStream(xml)) {
				return builder.parse(in);
			}
		} catch (ParserConfigurationException | SAXException e) {
			throw new IOException("CBEFF file is not readable XML", e);
		}
	}

	private static Element child(Element parent, String localName) {
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element && localName.equals(n.getLocalName())) {
				return (Element) n;
			}
		}
		return null;
	}

	private static String text(Element e) {
		return e == null ? null : e.getTextContent().trim();
	}
}
