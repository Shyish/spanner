/*
 * Copyright (C) 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package dk.ilios.spanner.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.propagateIfInstanceOf;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import dk.ilios.spanner.AfterRep;
import dk.ilios.spanner.BeforeRep;
import dk.ilios.spanner.Benchmark;
import dk.ilios.spanner.Macrobenchmark;
import dk.ilios.spanner.bridge.AbstractLogMessageVisitor;
import dk.ilios.spanner.bridge.GcLogMessage;
import dk.ilios.spanner.bridge.HotspotLogMessage;
import dk.ilios.spanner.bridge.StartMeasurementLogMessage;
import dk.ilios.spanner.bridge.StopMeasurementLogMessage;
import dk.ilios.spanner.exception.SkipThisScenarioException;
import dk.ilios.spanner.exception.TrialFailureException;
import dk.ilios.spanner.exception.UserCodeException;
import dk.ilios.spanner.internal.benchmark.BenchmarkMethods;
import dk.ilios.spanner.internal.trial.TrialSchedulingPolicy;
import dk.ilios.spanner.model.Measurement;
import dk.ilios.spanner.util.ShortDuration;
import dk.ilios.spanner.worker.MacrobenchmarkWorker;
import dk.ilios.spanner.worker.RuntimeWorker;
import dk.ilios.spanner.worker.Worker;
import dk.ilios.spanner.util.Reflection;
import dk.ilios.spanner.util.Util;

/**
 * The instrument responsible for measuring the runtime of {@link Benchmark} methods.
 */
public class RuntimeInstrument extends Instrument {

    private static final String SUGGEST_GRANULARITY_OPTION = "suggestGranularity";
    private static final String TIMING_INTERVAL_OPTION = "timingInterval";
    private static final int DRY_RUN_REPS = 1;

    private static final Logger logger = Logger.getLogger(RuntimeInstrument.class.getName());

    private final ShortDuration timerGranularityNanoSec;

    public RuntimeInstrument(ShortDuration timerGranularityNanoSec) {
        this.timerGranularityNanoSec = timerGranularityNanoSec;
    }

    @Override
    public boolean isBenchmarkMethod(Method method) {
        return method.isAnnotationPresent(Benchmark.class)
                || BenchmarkMethods.isTimeMethod(method)
                || method.isAnnotationPresent(Macrobenchmark.class);
    }

    @Override
    protected ImmutableSet<String> instrumentOptions() {
        return ImmutableSet.of(
                CommonInstrumentOptions.WARMUP.getKey(),
                CommonInstrumentOptions.MAX_WARMUP_WALL_TIME.getKey(),
                TIMING_INTERVAL_OPTION,
                CommonInstrumentOptions.MEASUREMENTS.getKey(),
                CommonInstrumentOptions.GC_BEFORE_EACH.getKey(),
                SUGGEST_GRANULARITY_OPTION);
    }

    @Override
    public Instrumentation createInstrumentation(Method benchmarkMethod) throws InvalidBenchmarkException {
        checkNotNull(benchmarkMethod);
        checkArgument(isBenchmarkMethod(benchmarkMethod));
        if (Util.isStatic(benchmarkMethod)) {
            throw new InvalidBenchmarkException("Benchmark methods must not be static: %s",
                    benchmarkMethod.getName());
        }
        try {
            switch (BenchmarkMethods.Type.of(benchmarkMethod)) {
                case MACRO:
                    return new MacrobenchmarkInstrumentation(benchmarkMethod);
                case MICRO:
                    return new MicrobenchmarkInstrumentation(benchmarkMethod);
                case PICO:
                    return new PicobenchmarkInstrumentation(benchmarkMethod);
                default:
                    throw new AssertionError("unknown type");
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidBenchmarkException("Benchmark methods must have no arguments or accept "
                    + "a single int or long parameter: %s", benchmarkMethod.getName());
        }
    }

    private class MacrobenchmarkInstrumentation extends Instrumentation {

        MacrobenchmarkInstrumentation(Method benchmarkMethod) {
            super(benchmarkMethod);
        }

        @Override
        public void dryRun(Object benchmark) throws UserCodeException {
            ImmutableSet<Method> beforeRepMethods = Reflection.getAnnotatedMethods(benchmarkMethod.getDeclaringClass(), BeforeRep.class);
            ImmutableSet<Method> afterRepMethods = Reflection.getAnnotatedMethods(benchmarkMethod.getDeclaringClass(), AfterRep.class);
            try {
                for (Method beforeRepMethod : beforeRepMethods) {
                    beforeRepMethod.invoke(benchmark);
                }
                try {
                    benchmarkMethod.invoke(benchmark);
                } finally {
                    for (Method afterRepMethod : afterRepMethods) {
                        afterRepMethod.invoke(benchmark);
                    }
                }
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            } catch (InvocationTargetException e) {
                Throwable userException = e.getCause();
                propagateIfInstanceOf(userException, SkipThisScenarioException.class);
                throw new UserCodeException(userException);
            }
        }

        @Override
        public Class<? extends Worker> workerClass() {
            return MacrobenchmarkWorker.class;
        }

        @Override
        MeasurementCollectingVisitor getMeasurementCollectingVisitor() {
//            return new SingleInvocationMeasurementCollector(
//                    Integer.parseInt(options.get(CommonInstrumentOptions.MEASUREMENTS.getKey())),
//                    ShortDuration.valueOf(options.get(CommonInstrumentOptions.WARMUP.getKey())),
//                    ShortDuration.valueOf(options.get(CommonInstrumentOptions.MAX_WARMUP_WALL_TIME.getKey())),
//                    timerGranularityNanoSec);

            return new SingleInvocationMeasurementCollector(
                    9,
                    ShortDuration.of(10, TimeUnit.SECONDS),
                    ShortDuration.of(10, TimeUnit.MINUTES),
                    timerGranularityNanoSec);
        }
    }

    @Override
    public TrialSchedulingPolicy schedulingPolicy() {
        // Runtime measurements are currently believed to be too sensitive to system performance to
        // allow parallel runners.
        // TODO(lukes): investigate this, on a multicore system it seems like we should be able to
        // allow some parallelism without corrupting results.
        return TrialSchedulingPolicy.SERIAL;
    }

    private abstract class RuntimeInstrumentation extends Instrumentation {
        RuntimeInstrumentation(Method method) {
            super(method);
        }

        @Override
        public void dryRun(Object benchmark) throws UserCodeException {
            try {
                benchmarkMethod.invoke(benchmark, DRY_RUN_REPS);
            } catch (IllegalAccessException impossible) {
                throw new AssertionError(impossible);
            } catch (InvocationTargetException e) {
                Throwable userException = e.getCause();
                propagateIfInstanceOf(userException, SkipThisScenarioException.class);
                throw new UserCodeException(userException);
            }
        }

        @Override
        public ImmutableMap<String, String> workerOptions() {
            return ImmutableMap.of(
                    TIMING_INTERVAL_OPTION + "Nanos", toNanosString(TIMING_INTERVAL_OPTION),
                    CommonInstrumentOptions.GC_BEFORE_EACH.getKey(), "true"
            );

//            return ImmutableMap.of(
//                    TIMING_INTERVAL_OPTION + "Nanos", toNanosString(TIMING_INTERVAL_OPTION),
//                    CommonInstrumentOptions.GC_BEFORE_EACH.getKey(), options.get(CommonInstrumentOptions.GC_BEFORE_EACH.getKey())
//            );
        }

        private String toNanosString(String optionName) {
            return String.valueOf(ShortDuration.of(500, TimeUnit.MILLISECONDS)); // From global-config.properties.
//            return String.valueOf(
//                    ShortDuration.valueOf(options.get(optionName)).to(NANOSECONDS));
        }

        @Override
        MeasurementCollectingVisitor getMeasurementCollectingVisitor() {
            return new RepetitionBasedMeasurementCollector(
                    9,
                    ShortDuration.of(0, TimeUnit.SECONDS),
                    ShortDuration.of(10, TimeUnit.MINUTES),
                    true,
                    timerGranularityNanoSec);
//
//
//            return new RepBasedMeasurementCollector(
//                    getMeasurementsPerTrial(),
//                    ShortDuration.valueOf(options.get(CommonInstrumentOptions.WARMUP.getKey())),
//                    ShortDuration.valueOf(options.get(CommonInstrumentOptions.MAX_WARMUP_WALL_TIME.getKey())),
//                    Boolean.parseBoolean(options.get(SUGGEST_GRANULARITY_OPTION)),
//                    timerGranularityNanoSec);
        }
    }

    private class MicrobenchmarkInstrumentation extends RuntimeInstrumentation {
        MicrobenchmarkInstrumentation(Method benchmarkMethod) {
            super(benchmarkMethod);
        }

        @Override
        public Class<? extends Worker> workerClass() {
            return RuntimeWorker.Micro.class;
        }
    }

    private int getMeasurementsPerTrial() {
        String measurementsString = options.get(CommonInstrumentOptions.MEASUREMENTS.getKey());
        int measurementsPerTrial = (measurementsString == null) ? 1 : Integer.parseInt(measurementsString);
        // TODO(gak): fail faster
        checkState(measurementsPerTrial > 0);
        return measurementsPerTrial;
    }

    private class PicobenchmarkInstrumentation extends RuntimeInstrumentation {
        PicobenchmarkInstrumentation(Method benchmarkMethod) {
            super(benchmarkMethod);
        }

        @Override
        public Class<? extends Worker> workerClass() {
            return RuntimeWorker.Pico.class;
        }
    }

    private abstract static class RuntimeMeasurementCollector extends AbstractLogMessageVisitor
            implements MeasurementCollectingVisitor {
        final int targetMeasurements;
        final ShortDuration warmup;
        final ShortDuration maxWarmupWallTime;
        final List<Measurement> measurements = Lists.newArrayList();
        ShortDuration elapsedWarmup = ShortDuration.zero();
        boolean measuring = false;
        boolean invalidateMeasurements = false;
        boolean notifiedAboutGc = false;
        boolean notifiedAboutJit = false;
        boolean notifiedAboutMeasuringJit = false;
        Stopwatch timeSinceStartOfTrial = Stopwatch.createUnstarted();
        final List<String> messages = Lists.newArrayList();
        final ShortDuration nanoTimeGranularity;

        RuntimeMeasurementCollector(
                int targetMeasurements,
                ShortDuration warmup,
                ShortDuration maxWarmupWallTime,
                ShortDuration nanoTimeGranularity) {
            this.targetMeasurements = targetMeasurements;
            this.warmup = warmup;
            this.maxWarmupWallTime = maxWarmupWallTime;
            this.nanoTimeGranularity = nanoTimeGranularity;
        }

        @Override
        public void visit(GcLogMessage logMessage) {
            if (measuring && isWarmupComplete() && !notifiedAboutGc) {
                gcWhileMeasuring();
                notifiedAboutGc = true;
            }
        }

        abstract void gcWhileMeasuring();

        @Override
        public void visit(HotspotLogMessage logMessage) {
            if (isWarmupComplete()) {
                if (measuring && notifiedAboutMeasuringJit) {
                    hotspotWhileMeasuring();
                    notifiedAboutMeasuringJit = true;
                } else if (notifiedAboutJit) {
                    hotspotWhileNotMeasuring();
                    notifiedAboutJit = true;
                }
            }
        }

        abstract void hotspotWhileMeasuring();

        abstract void hotspotWhileNotMeasuring();

        @Override
        public void visit(StartMeasurementLogMessage logMessage) {
            checkState(!measuring);
            measuring = true;
            if (!timeSinceStartOfTrial.isRunning()) {
                timeSinceStartOfTrial.start();
            }
        }

        @Override
        public void visit(StopMeasurementLogMessage logMessage) {
            checkState(measuring);
            Collection<Measurement> newMeasurements = logMessage.measurements();
            if (!isWarmupComplete()) {
                for (Measurement measurement : newMeasurements) {
                    // TODO(gak): eventually we will need to resolve different units
                    checkArgument("ns".equals(measurement.value().unit()));
                    elapsedWarmup = elapsedWarmup.plus(
                            ShortDuration.of(BigDecimal.valueOf(measurement.value().magnitude()), NANOSECONDS));
                    validateMeasurement(measurement);
                }
            } else {
                if (!measuredWarmupDurationReached()) {
                    messages.add(String.format(
                            "WARNING: Warmup was interrupted because it took longer than %s of wall-clock time. "
                                    + "%s was spent in the benchmark method for warmup "
                                    + "(normal warmup duration should be %s).",
                            maxWarmupWallTime, elapsedWarmup, warmup));
                }

                if (invalidateMeasurements) {
                    logger.fine(String.format("Discarding %s as they were marked invalid.", newMeasurements));
                } else {
                    this.measurements.addAll(newMeasurements);
                }
            }
            invalidateMeasurements = false;
            measuring = false;
        }

        abstract void validateMeasurement(Measurement measurement);

        @Override
        public ImmutableList<Measurement> getMeasurements() {
            return ImmutableList.copyOf(measurements);
        }

        boolean measuredWarmupDurationReached() {
            return elapsedWarmup.compareTo(warmup) >= 0;
        }

        @Override
        public boolean isWarmupComplete() {
            // Fast macro-benchmarks (up to tens of ms) need lots of measurements to reach 10s of
            // measured warmup time. Because of the per-measurement overhead of running @BeforeRep and
            // @AfterRep, warmup can take very long.
            //
            // To prevent this, we enforce a cap on the wall-clock time here.
            return measuredWarmupDurationReached()
                    || timeSinceStartOfTrial.elapsed(MILLISECONDS) > maxWarmupWallTime.to(MILLISECONDS);
        }

        @Override
        public boolean isDoneCollecting() {
            return measurements.size() >= targetMeasurements;
        }

        @Override
        public ImmutableList<String> getMessages() {
            return ImmutableList.copyOf(messages);
        }
    }

    private static final class RepetitionBasedMeasurementCollector extends RuntimeMeasurementCollector {
        final boolean suggestGranularity;
        boolean notifiedAboutGranularity = false;

        RepetitionBasedMeasurementCollector(
                int measurementsPerTrial,
                ShortDuration warmup,
                ShortDuration maxWarmupWallTime,
                boolean suggestGranularity,
                ShortDuration nanoTimeGranularity) {
            super(measurementsPerTrial, warmup, maxWarmupWallTime, nanoTimeGranularity);
            this.suggestGranularity = suggestGranularity;
        }

        @Override
        void gcWhileMeasuring() {
            invalidateMeasurements = true;
            messages.add("ERROR: GC occurred during timing. Measurements were discarded.");
        }

        @Override
        void hotspotWhileMeasuring() {
            invalidateMeasurements = true;
            messages.add(
                    "ERROR: Hotspot compilation occurred during timing: warmup is likely insufficent. "
                            + "Measurements were discarded.");
        }

        @Override
        void hotspotWhileNotMeasuring() {
            messages.add(
                    "WARNING: Hotspot compilation occurred after warmup, but outside of timing. "
                            + "Results may be affected. Run with --verbose to see which method was compiled.");
        }

        @Override
        void validateMeasurement(Measurement measurement) {
            if (suggestGranularity) {
                double nanos = measurement.value().magnitude() / measurement.weight();
                if (!notifiedAboutGranularity && ((nanos / 1000) > nanoTimeGranularity.to(NANOSECONDS))) {
                    notifiedAboutGranularity = true;
                    ShortDuration reasonableUpperBound = nanoTimeGranularity.times(1000);
                    messages.add(String.format("INFO: This experiment does not require a microbenchmark. "
                                    + "The granularity of the timer (%s) is less than 0.1%% of the measured runtime. "
                                    + "If all experiments for this benchmark have runtimes greater than %s, "
                                    + "consider the macrobenchmark instrument.", nanoTimeGranularity,
                            reasonableUpperBound));
                }
            }
        }
    }

    private static final class SingleInvocationMeasurementCollector extends RuntimeMeasurementCollector {

        SingleInvocationMeasurementCollector(
                int measurementsPerTrial,
                ShortDuration warmup,
                ShortDuration maxWarmupWallTime,
                ShortDuration nanoTimeGranularity) {
            super(measurementsPerTrial, warmup, maxWarmupWallTime, nanoTimeGranularity);
        }

        @Override
        void gcWhileMeasuring() {
            messages.add("WARNING: GC occurred during timing. "
                    + "Depending on the scope of the benchmark, this might significantly impact results. "
                    + "Consider running with a larger heap size.");
        }

        @Override
        void hotspotWhileMeasuring() {
            messages.add("WARNING: Hotspot compilation occurred during timing. "
                    + "Depending on the scope of the benchmark, this might significantly impact results. "
                    + "Consider running with a longer warmup.");
        }

        @Override
        void hotspotWhileNotMeasuring() {
            messages.add(
                    "WARNING: Hotspot compilation occurred after warmup, but outside of timing. "
                            + "Depending on the scope of the benchmark, this might significantly impact results. "
                            + "Consider running with a longer warmup.");
        }

        @Override
        void validateMeasurement(Measurement measurement) {
            double nanos = measurement.value().magnitude() / measurement.weight();
            if ((nanos / 1000) < nanoTimeGranularity.to(NANOSECONDS)) {
                ShortDuration runtime = ShortDuration.of(BigDecimal.valueOf(nanos), NANOSECONDS);
                throw new TrialFailureException(String.format(
                        "This experiment requires a microbenchmark. "
                                + "The granularity of the timer (%s) "
                                + "is greater than 0.1%% of the measured runtime (%s). "
                                + "Use the microbenchmark instrument for accurate measurements.",
                        nanoTimeGranularity, runtime));
            }
        }
    }
}

