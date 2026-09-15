/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.fake;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * The reader's side of the marker contract, written by a fixture. The event name and the field
 * names must match {@code eu.exeris.kernel.tck.contract.AllocationWindowEvent}; the TCK's own
 * self-test proves the writer and this fixture proves the reader. {@code MarkerVocabularyTest}
 * checks the two against each other, field for field, so a rename cannot leave both suites green.
 */
@Name("eu.exeris.tck.AllocationWindow")
@Label("TCK allocation measurement window (fixture)")
@Category({"Exeris", "TCK"})
@StackTrace(false)
public final class AllocationWindowFixtureEvent extends Event {

    @Label("Boundary")
    public String boundary;

    @Label("Measurement id")
    public long measurementId;

    @Label("Subsystem")
    public String subsystem;

    @Label("Test class")
    public String testClass;

    @Label("Iterations")
    public int iterations;

    @Label("Workload thread id")
    public long workloadThreadId;

    @Label("Contract mode")
    public String contractMode;

    @Label("Budget per iteration")
    public int budgetPerIteration;

    @Label("Budget bytes per iteration")
    public double budgetBytesPerIteration;

    @Label("Allocated bytes delta")
    public long allocatedBytesDelta;
}
