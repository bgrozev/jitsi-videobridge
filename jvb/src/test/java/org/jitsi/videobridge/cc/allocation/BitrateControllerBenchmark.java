package org.jitsi.videobridge.cc.allocation;

import org.jitsi.videobridge.util.*;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.*;

@Fork(value = 1)
@Warmup(iterations = 3)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class BitrateControllerBenchmark
{
    private BitrateControllerBenchmarkImpl b = new BitrateControllerBenchmarkImpl();
    static int i = 0;

    @Setup(Level.Invocation)
    public void setup()
    {
        i++;
        System.err.println("Setting up BitrateControllerBenchmark");
        b = new BitrateControllerBenchmarkImpl();
    }

    @Benchmark
    public void tileView()
    {
        b.tileView();
    }

    @Benchmark
    public void stageView()
    {
        b.stageView();
    }

    @TearDown
    public void shutdown()
    {
        System.err.println("Shutting down BitrateControllerBenchmark, i="+i);
        TaskPools.SCHEDULED_POOL.shutdown();
        TaskPools.CPU_POOL.shutdown();
        TaskPools.IO_POOL.shutdown();
    }
}
