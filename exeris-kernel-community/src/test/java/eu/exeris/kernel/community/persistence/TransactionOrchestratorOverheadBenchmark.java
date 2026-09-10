/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.core.persistence.TransactionOrchestrator;
import eu.exeris.kernel.core.persistence.TransactionRetryPolicy;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.tck.perf.AbstractExerisBenchmark;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing TransactionOrchestrator baseline query overhead
 * versus full executeManaged lifecycle overhead under disabled JFR events.
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class TransactionOrchestratorOverheadBenchmark extends AbstractExerisBenchmark {

    private static final String PROP_URL  = "benchmark.db.url";
    private static final String PROP_USER = "benchmark.db.user";
    private static final String PROP_PASS = "benchmark.db.password";

    private static final String H2_URL =
            "jdbc:h2:mem:benchmark_tx_overhead_"
            + TransactionOrchestratorOverheadBenchmark.class.getSimpleName()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    private CommunityPersistenceEngine engine;
    private TransactionOrchestrator orchestrator;

    @Setup(Level.Trial)
    public void setUp() {
        String url  = System.getProperty(PROP_URL,  H2_URL);
        String user = System.getProperty(PROP_USER, "sa");
        String pass = System.getProperty(PROP_PASS, "");

        PersistenceConfig config = PersistenceConfig.defaults(url, user, pass);

        engine = new CommunityPersistenceEngine(config);
        orchestrator = new TransactionOrchestrator(
                engine,
                TransactionRetryPolicy.NONE,
                ImmutableStorageContext.GLOBAL
        );
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        engine.close();
    }

    @Benchmark
    public long query_trivial() {
        return orchestrator.query(conn -> {
            try (var result = conn.executeQuery("SELECT 1")) {
                result.next();
                return result.row().getLong(0);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Benchmark
    public void executeManaged_trivial() {
        orchestrator.executeManaged(conn -> {
            try (var result = conn.executeQuery("SELECT 1")) {
                result.next();
                result.row().getLong(0);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }
}