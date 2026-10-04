/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.engine.LivenessMetrics;
import io.mosip.registration.config.AppConfig;

/**
 * Publishes the anonymised liveness metrics through the client's Micrometer registry, which writes them to
 * {@code logs/metrics.log} every 60 s together with the other client metrics.
 *
 * <p>Counters: sessions, retries, passes, passive passes, active triggers, challenges passed, frames analysed and
 * failures per reason ({@code liveness.failures.PAD}, ...). Gauges: average total, passive, challenge and
 * per-frame time in milliseconds, retry rate and failure rate. No user IDs, registration IDs or device serials
 * appear in names or tags. Nothing is registered when {@code mosip.registration.liveness.metrics.enable=N} at
 * start-up.
 */
@Component
public class LivenessMeterBinder implements MeterBinder {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessMeterBinder.class);

	private final LivenessEngineRegistry engines;
	private final LivenessConfig config;

	@Autowired(required = false)
	private MeterRegistry registry;

	/**
	 * Creates the binder.
	 *
	 * @param engines the engine registry that owns the metrics
	 * @param config  liveness configuration
	 */
	@Autowired
	public LivenessMeterBinder(LivenessEngineRegistry engines, LivenessConfig config) {
		this.engines = Objects.requireNonNull(engines, "engines");
		this.config = Objects.requireNonNull(config, "config");
	}

	/** Registers the meters with the client registry, if there is one and metrics are enabled. */
	@PostConstruct
	public void register() {
		if (registry == null || !config.isMetricsEnabled()) {
			LOGGER.info("Liveness metrics are not published");
			return;
		}
		bindTo(registry);
	}

	@Override
	public void bindTo(MeterRegistry meterRegistry) {
		LivenessMetrics metrics = engines.metrics();
		for (Map.Entry<String, Long> e : metrics.snapshot().counters().entrySet()) {
			String name = e.getKey();
			FunctionCounter.builder(name, metrics, m -> m.snapshot().counter(name)).register(meterRegistry);
		}
		gauge(meterRegistry, metrics, "liveness.time.total.avg_ms", m -> m.snapshot().averageTotalMs());
		gauge(meterRegistry, metrics, "liveness.time.passive.avg_ms", m -> m.snapshot().averagePassiveMs());
		gauge(meterRegistry, metrics, "liveness.time.challenge.avg_ms", m -> m.snapshot().averageChallengeMs());
		gauge(meterRegistry, metrics, "liveness.time.frame.avg_ms", m -> m.snapshot().averageFrameMs());
		gauge(meterRegistry, metrics, "liveness.rate.retry", m -> m.snapshot().retryRate());
		gauge(meterRegistry, metrics, "liveness.rate.failure", m -> m.snapshot().failureRate());
	}

	private static void gauge(MeterRegistry registry, LivenessMetrics metrics, String name,
			ToDoubleFunction<LivenessMetrics> value) {
		Gauge.builder(name, metrics, value).strongReference(true).register(registry);
	}
}
