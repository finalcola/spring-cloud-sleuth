/*
 * Copyright 2013-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.autoconfig.zipkin2;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.PreDestroy;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import zipkin2.CheckResult;
import zipkin2.Span;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.InMemoryReporterMetrics;
import zipkin2.reporter.Reporter;
import zipkin2.reporter.ReporterMetrics;
import zipkin2.reporter.Sender;
import zipkin2.reporter.metrics.micrometer.MicrometerReporterMetrics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.cloud.sleuth.autoconfig.brave.BraveAutoConfiguration;
import org.springframework.cloud.sleuth.zipkin2.DefaultEndpointLocator;
import org.springframework.cloud.sleuth.zipkin2.DefaultZipkinRestTemplateCustomizer;
import org.springframework.cloud.sleuth.zipkin2.EndpointLocator;
import org.springframework.cloud.sleuth.zipkin2.ZipkinProperties;
import org.springframework.cloud.sleuth.zipkin2.ZipkinRestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

/**
 * {@link org.springframework.boot.autoconfigure.EnableAutoConfiguration
 * Auto-configuration} enables reporting to Zipkin via HTTP.
 *
 * The {@link ZipkinRestTemplateCustomizer} allows you to customize the
 * {@link RestTemplate} that is used to send Spans to Zipkin. Its default implementation -
 * {@link DefaultZipkinRestTemplateCustomizer} adds the GZip compression.
 *
 * @author Spencer Gibb
 * @author Tim Ysewyn
 * @since 1.0.0
 * @see ZipkinRestTemplateCustomizer
 * @see DefaultZipkinRestTemplateCustomizer
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ZipkinProperties.class)
@ConditionalOnClass({ Sender.class, EndpointLocator.class })
@ConditionalOnProperty(value = { "spring.sleuth.enabled", "spring.zipkin.enabled" }, matchIfMissing = true)
@AutoConfigureAfter(name = "org.springframework.cloud.autoconfigure.RefreshAutoConfiguration")
@AutoConfigureBefore(BraveAutoConfiguration.class)
@Import({ ZipkinSenderConfigurationImportSelector.class, ZipkinBraveConfiguration.class })
public class ZipkinAutoConfiguration {

	private final ExecutorService zipkinExecutor = Executors.newSingleThreadExecutor();

	/**
	 * Zipkin reporter bean name. Name of the bean matters for supporting multiple tracing
	 * systems.
	 */
	public static final String REPORTER_BEAN_NAME = "zipkinReporter";

	/**
	 * Zipkin sender bean name. Name of the bean matters for supporting multiple tracing
	 * systems.
	 */
	public static final String SENDER_BEAN_NAME = "zipkinSender";

	private static final Log log = LogFactory.getLog(ZipkinAutoConfiguration.class);

	@PreDestroy
	void cleanup() {
		this.zipkinExecutor.shutdown();
	}

	/** Limits {@link Sender#check()} to {@code deadlineMillis}. */
	static CheckResult checkResult(ExecutorService zipkinExecutor, Sender sender, long deadlineMillis) {
		Future<CheckResult> future = zipkinExecutor.submit(sender::check);
		try {
			return future.get(deadlineMillis, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			return CheckResult.failed(new TimeoutException("Timed out after " + deadlineMillis + "ms"));
		}
		catch (Exception e) {
			return CheckResult.failed(e);
		}
	}

	@Bean(REPORTER_BEAN_NAME)
	@ConditionalOnMissingBean(name = REPORTER_BEAN_NAME)
	Reporter<Span> reporter(ReporterMetrics reporterMetrics, ZipkinProperties zipkin,
			@Qualifier(SENDER_BEAN_NAME) Sender sender) {
		CheckResult checkResult = checkResult(zipkinExecutor, sender, 1_000L);
		logCheckResult(sender, checkResult);

		// historical constraint. Note: AsyncReporter supports memory bounds
		AsyncReporter<Span> asyncReporter = AsyncReporter.builder(sender).queuedMaxSpans(1000)
				.messageTimeout(zipkin.getMessageTimeout(), TimeUnit.SECONDS).metrics(reporterMetrics)
				.build(zipkin.getEncoder());

		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				log.info("Flushing remaining spans on shutdown");
				asyncReporter.flush();
				try {
					Thread.sleep(TimeUnit.SECONDS.toMillis(zipkin.getMessageTimeout()) + 500);
					log.debug("Flushing done - closing the reporter");
					asyncReporter.close();
				}
				catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}
		});

		return asyncReporter;
	}

	private void logCheckResult(Sender sender, CheckResult checkResult) {
		if (log.isDebugEnabled() && checkResult != null && checkResult.ok()) {
			log.debug("Check result of the [" + sender.toString() + "] is [" + checkResult + "]");
		}
		else if (checkResult != null && !checkResult.ok()) {
			log.warn("Check result of the [" + sender.toString() + "] contains an error [" + checkResult + "]");
		}
	}

	@Bean
	@ConditionalOnMissingBean
	ZipkinRestTemplateCustomizer zipkinRestTemplateCustomizer(ZipkinProperties zipkinProperties) {
		return new DefaultZipkinRestTemplateCustomizer(zipkinProperties);
	}

	@Bean
	@ConditionalOnMissingBean
	ReporterMetrics sleuthReporterMetrics() {
		return new InMemoryReporterMetrics();
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(EndpointLocator.class)
	@ConditionalOnProperty(value = "spring.zipkin.locator.discovery.enabled", havingValue = "false",
			matchIfMissing = true)
	protected static class DefaultEndpointLocatorConfiguration {

		@Autowired(required = false)
		private ServerProperties serverProperties;

		@Autowired
		private ZipkinProperties zipkinProperties;

		@Autowired(required = false)
		private InetUtils inetUtils;

		@Autowired
		private Environment environment;

		@Bean
		public EndpointLocator zipkinEndpointLocator() {
			return new DefaultEndpointLocator(null, this.serverProperties, this.environment, this.zipkinProperties,
					this.inetUtils);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(Registration.class)
	@ConditionalOnMissingBean(EndpointLocator.class)
	@ConditionalOnProperty(value = "spring.zipkin.locator.discovery.enabled", havingValue = "true")
	protected static class RegistrationEndpointLocatorConfiguration {

		@Autowired(required = false)
		private ServerProperties serverProperties;

		@Autowired
		private ZipkinProperties zipkinProperties;

		@Autowired(required = false)
		private InetUtils inetUtils;

		@Autowired
		private Environment environment;

		@Autowired(required = false)
		private Registration registration;

		@Bean
		public EndpointLocator zipkinEndpointLocator() {
			return new DefaultEndpointLocator(this.registration, this.serverProperties, this.environment,
					this.zipkinProperties, this.inetUtils);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingClass("io.micrometer.core.instrument.MeterRegistry")
	static class TraceMetricsInMemoryConfiguration {

		@Bean
		@ConditionalOnMissingBean
		ReporterMetrics sleuthReporterMetrics() {
			return new InMemoryReporterMetrics();
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(MeterRegistry.class)
	static class TraceMetricsMicrometerConfiguration {

		@Configuration(proxyBeanMethods = false)
		@ConditionalOnMissingBean(ReporterMetrics.class)
		static class NoReporterMetricsBeanConfiguration {

			@Bean
			@ConditionalOnBean(MeterRegistry.class)
			@ConditionalOnClass(name = "zipkin2.reporter.metrics.micrometer.MicrometerReporterMetrics")
			ReporterMetrics sleuthMicrometerReporterMetrics(MeterRegistry meterRegistry) {
				return MicrometerReporterMetrics.create(meterRegistry);
			}

			@Bean
			@ConditionalOnMissingClass("zipkin2.reporter.metrics.micrometer.MicrometerReporterMetrics")
			ReporterMetrics sleuthReporterMetrics() {
				return new InMemoryReporterMetrics();
			}

		}

	}

}
