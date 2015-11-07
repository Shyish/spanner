/*
 * Copyright (C) 2015 Christian Melchior.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.ilios.spanner.benchmarks.valid;

import org.junit.runner.RunWith;

import dk.ilios.spanner.Benchmark;
import dk.ilios.spanner.junit.SpannerRunner;

@RunWith(SpannerRunner.class)
public class ValidBenchmarkMethods {

    @Benchmark
    public void noParams() {
        sleep(10);
    }

    @Benchmark
    public void intReps(int reps) {
        sleep(10);
    }

    @Benchmark
    public void longReps(long reps) {
        sleep(10);
    }

    @Benchmark
    public boolean anyReturnType(int reps) {
        sleep(10);
        return false;
    }

    private void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
