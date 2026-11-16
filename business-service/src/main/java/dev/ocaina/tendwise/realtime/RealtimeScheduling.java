package dev.ocaina.tendwise.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled for the SSE heartbeat ({@link EventStreams#sendHeartbeats()}). It is always
 * on, unlike the job poller, which the tests switch off.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RealtimeScheduling {}
