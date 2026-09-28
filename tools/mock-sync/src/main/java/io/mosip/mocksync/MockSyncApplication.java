package io.mosip.mocksync;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;

import io.mosip.mocksync.config.MockSyncProperties;
import io.mosip.mocksync.crypto.KeyMaterial;
import io.mosip.mocksync.crypto.ServerKeys;

/**
 * MockSync: a local stand-in for the MOSIP server APIs the Registration Clients use (syncdata, IDA onboarding,
 * packet receiver). It serves HTTPS on port 8443 for the desktop client and plain HTTP on port 8080 for the Android
 * debug build.
 */
@SpringBootApplication
@EnableConfigurationProperties(MockSyncProperties.class)
public class MockSyncApplication {

	/**
	 * Starts MockSync. The HTTPS key store is generated into the work directory before the web server starts.
	 *
	 * @param args Spring Boot arguments, e.g. {@code --mocksync.work-dir=work}
	 * @throws IOException if the key store cannot be written
	 */
	public static void main(String[] args) throws IOException {
		Path workDir = Path.of(argument(args, "mocksync.work-dir", System.getenv().getOrDefault("MOCKSYNC_WORK_DIR", "work")));
		Path tlsStore = ServerKeys.ensureTlsKeyStore(workDir);

		Map<String, Object> defaults = new HashMap<>();
		defaults.put("mocksync.work-dir", workDir.toString());
		defaults.put("server.ssl.key-store", tlsStore.toAbsolutePath().toString());
		defaults.put("server.ssl.key-store-type", "PKCS12");
		defaults.put("server.ssl.key-store-password", new String(KeyMaterial.STORE_PASSWORD));

		SpringApplication application = new SpringApplication(MockSyncApplication.class);
		application.setDefaultProperties(defaults);
		application.run(args);
	}

	/**
	 * Adds the plain HTTP connector ({@code mocksync.http-port}) next to the HTTPS one.
	 *
	 * @param properties MockSync settings
	 * @return customizer
	 */
	@Bean
	public WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpConnector(MockSyncProperties properties) {
		return factory -> {
			if (properties.getHttpPort() > 0) {
				Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
				connector.setPort(properties.getHttpPort());
				factory.addAdditionalTomcatConnectors(connector);
			}
		};
	}

	private static String argument(String[] args, String name, String fallback) {
		String prefix = "--" + name + "=";
		for (String arg : args) {
			if (arg.startsWith(prefix)) {
				return arg.substring(prefix.length());
			}
		}
		return System.getProperty(name, fallback);
	}
}
