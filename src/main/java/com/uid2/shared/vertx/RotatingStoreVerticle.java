// Copyright (c) 2021 The Trade Desk, Inc
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice,
//    this list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package com.uid2.shared.vertx;

import com.uid2.shared.health.HealthComponent;
import com.uid2.shared.health.HealthManager;
import com.uid2.shared.store.IMetadataVersionedStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

public class RotatingStoreVerticle extends AbstractVerticle {
    private static final Logger LOGGER = LoggerFactory.getLogger(RotatingStoreVerticle.class);
    private final String storeName;
    private final HealthComponent healthComponent;
    private final Counter counterStoreRefreshTimeMs;
    private final Counter counterStoreRefreshed;
    private final Gauge gaugeStoreVersion;
    private final Gauge gaugeStoreEntryCount;
    private final IMetadataVersionedStore versionedStore;
    private final AtomicLong latestVersion = new AtomicLong(-1L);
    private final AtomicLong latestEntryCount = new AtomicLong(-1L);

    private final long refreshIntervalMs;

    public RotatingStoreVerticle(String storeName, long refreshIntervalMs, IMetadataVersionedStore versionedStore) {
        this.healthComponent = HealthManager.instance.registerComponent(storeName + "-rotator");
        this.healthComponent.setHealthStatus(false, "not started");

        this.storeName = storeName;
        this.counterStoreRefreshed = Counter
            .builder("uid2.config_store.refreshed")
            .tag("store", storeName)
            .description("counter for how many times " + storeName + "store is refreshed")
            .register(Metrics.globalRegistry);
        this.counterStoreRefreshTimeMs = Counter
            .builder("uid2.config_store.refreshtime_ms")
            .tag("store", storeName)
            .description("counter for total time (ms) " + storeName + "store spend in refreshing")
            .register(Metrics.globalRegistry);
        this.gaugeStoreVersion = Gauge
            .builder("uid2.config_store.version", () -> this.latestVersion.get())
            .tag("store", storeName)
            .description("gauge for " + storeName + "store version")
            .register(Metrics.globalRegistry);
        this.gaugeStoreEntryCount = Gauge
            .builder("uid2.config_store.entry_count", () -> this.latestEntryCount.get())
            .tag("store", storeName)
            .description("gauge for " + storeName + "store total entry count")
            .register(Metrics.globalRegistry);
        this.versionedStore = versionedStore;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        this.healthComponent.setHealthStatus(false, "still starting");
        this.startRefresh(startPromise);
    }

    private void startRefresh(Promise<Void> promise) {
        LOGGER.info("Starting " + this.storeName + " loading");
        vertx.executeBlocking(p -> {
            try {
                this.refresh();
                p.complete();
            } catch (Exception e) {
                p.fail(e);
            }
        }, ar -> {
            if (ar.succeeded()) {
                this.healthComponent.setHealthStatus(true);
                promise.complete();
                LOGGER.info("Successful " + this.storeName + " loading. Starting Background Refresh");
                this.startBackgroundRefresh();
            } else {
                this.healthComponent.setHealthStatus(false, ar.cause().getMessage());
                LOGGER.error("Failed " + this.storeName + " loading. Trying again in " + refreshIntervalMs + "ms", ar.cause());
                vertx.setTimer(refreshIntervalMs, id -> this.startRefresh(promise));
            }
        });
    }

    private void startBackgroundRefresh() {
        vertx.setPeriodic(this.refreshIntervalMs, (id) -> {
            final long start = System.nanoTime();

            vertx.executeBlocking(promise -> {
                    try {
                        this.refresh();
                        promise.complete();
                    } catch (Exception e) {
                        promise.fail(e);
                    }
                },
                asyncResult -> {
                    final long end = System.nanoTime();
                    final long elapsed = ((end - start) / 1000000);
                    this.counterStoreRefreshTimeMs.increment(elapsed);
                    if (asyncResult.failed()) {
                        LOGGER.error("Failed to load " + this.storeName + ", " + elapsed + " ms", asyncResult.cause());
                    } else {
                        this.counterStoreRefreshed.increment();
                        LOGGER.trace("Successfully refreshed " + this.storeName + ", " + elapsed + " ms");
                    }
                }
            );
        });
    }

    public synchronized void refresh() throws Exception {
        final JsonObject metadata = this.versionedStore.getMetadata();
        final long version = this.versionedStore.getVersion(metadata);
        if (version > this.latestVersion.get()) {
            long entryCount = this.versionedStore.loadContent(metadata);
            this.latestVersion.set(version);
            this.latestEntryCount.set(entryCount);
            LOGGER.info("Successfully loaded " + this.storeName + " version " + version);
        }
    }
}
