package ca.spottedleaf.moonrise.common.time;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Originally the subclasses here were a lot nicer, they have now been squashed into ... something? but they have a much smaller memory footprint.
 * Furthermore, originally this class was a lot cleaner, but this code is extremely hot, and therefore needs as small a footprint as possible.
 * So I present, TickData revision 900
 */
public final class TickData {
    private final long interval; // ns

    // really simple circular buffer instead of that deque
    private final TickTime[] buffer;
    private final int capacity;
    private int index = 0;

    private long dumbTicksTotalTime = 0;
    private long dumbTicksTotalCPUTime = 0;

    // pre allocate our arrays
    private final long[] preDiffFromLast;
    private final long[] preTickTimes;
    private final long[] preTickCpuTimes;
    private final TickTime[] preSnap;

    public TickData(final long intervalNS) {
        this.interval = intervalNS;
        this.capacity = (int) Math.max(40, TimeUnit.NANOSECONDS.toSeconds(intervalNS) * 40);
        this.buffer = new TickTime[this.capacity];

        this.preDiffFromLast = new long[this.capacity];
        this.preTickTimes = new long[this.capacity];
        this.preTickCpuTimes = new long[this.capacity];
        this.preSnap = new TickTime[this.capacity];
    }

    public void addDataFrom(final TickTime time) {
        // aggregate dumb ticks
        // this means that we are no longer as accurate as we previously were
        // however this is accurate enough, if you disagree, go find a hill to cry on
        if (!time.isTickExecution()) {
            if (time.tickCpuTime() < 10_000 && time.tickLength() < 10_000) {
                dumbTicksTotalTime += time.tickLength();
                dumbTicksTotalCPUTime += time.tickCpuTime();
                return;
            }
        }

        buffer[index % capacity] = time;
        ++index;
    }

    private TickTime[] snapshot() {
        if (index <= capacity) {
            System.arraycopy(buffer, 0, preSnap, 0, index);
            Arrays.fill(preSnap, index, capacity, null);
        } else {
            int start = index % capacity;
            int firstLen = capacity - start;
            System.arraycopy(buffer, start, preSnap, 0, firstLen);
            System.arraycopy(buffer, 0, preSnap, firstLen, capacity - firstLen);
        }

        return preSnap;
    }

    public double getTPSAverage(final TickTime inProgress, final long tickInterval) {
        return getTPSAverage(inProgress, tickInterval, false);
    }

    public double getTPSAverage(TickTime inProgress, long tickInterval, boolean createFakeTick) {
        TickTime[] times = snapshot();
        if (times[0] == null && inProgress == null) return 1.0E9 / (double) tickInterval;

        CollapsedData data = collapseData(times, inProgress, tickInterval, createFakeTick);
        if (data.count == 0) return 1.0E9 / (double) tickInterval;

        long total = 0L;
        for (int i = 0; i < data.count; i++) total += data.diffFromLast[i];
        return (double) data.count / ((double) total / 1.0E9);
    }

    public MSPTData getMSPTData(final TickTime inProgress, final long tickInterval) {
        return getMSPTData(inProgress, tickInterval, false);
    }

    public MSPTData getMSPTData(TickTime inProgress, long tickInterval, boolean createFakeTick) {
        TickTime[] times = snapshot();
        if (times[0] == null && inProgress == null) return null;

        CollapsedData data = collapseData(times, inProgress, tickInterval, createFakeTick);
        if (data.count == 0) return null;

        long total = 0L;
        long[] tickTimeCopy = new long[data.count];
        for (int i = 0; i < data.count; i++) {
            total += data.tickTimes[i];
            tickTimeCopy[i] = data.tickTimes[i];
        }

        return new MSPTData((double) total / data.count * 1.0E-6, tickTimeCopy);
    }

    public TickReportData generateTickReport(final TickTime inProgress, final long endTime, final long tickInterval) {
        return generateTickReport(inProgress, endTime, tickInterval, false);
    }

    // rets null if there is no data or data is invalid
    public TickReportData generateTickReport(TickTime inProgress, long endTime, long tickInterval, boolean createFakeTick) {
        TickTime[] times = snapshot();
        if ((times.length == 0 || times[0] == null) && inProgress == null) return null;

        CollapsedData data = collapseData(times, inProgress, tickInterval, createFakeTick);
        if (data.count == 0) return null;

        final TickTime firstTick = times[0] != null ? times[0] : inProgress;
        final TickTime lastTick = times[times.length - 1] != null ? times[times.length - 1] : inProgress;

        if (firstTick == null || lastTick == null) return null;

        final long intervalStart = firstTick.tickStart();
        final long intervalEnd = (inProgress != null ? inProgress : lastTick).tickEnd();

        long totalTimeOverInterval = 0L;
        final long measureStart = endTime - this.interval;
        for (TickTime time : times) {
            if (time == null) continue;

            if (time.tickStart() < measureStart) {
                long diff = time.tickEnd() - measureStart;
                if (diff > 0L) totalTimeOverInterval += diff;
            } else {
                totalTimeOverInterval += time.tickLength();
            }
        }
        if (inProgress != null) {
            if (inProgress.tickStart() < measureStart) {
                long diff = inProgress.tickEnd() - measureStart;
                if (diff > 0L) totalTimeOverInterval += diff;
            } else {
                totalTimeOverInterval += inProgress.tickLength();
            }
        }

        long[] tickStartToStartDifferences = Arrays.copyOf(data.diffFromLast, data.count);
        long[] timePerTickDataRaw = Arrays.copyOf(data.tickTimes, data.count);
        long[] missingCPUTimeDataRaw = new long[data.count];
        long totalTimeTicking = 0L;

        for (int i = 0; i < data.count; i++) {
            missingCPUTimeDataRaw[i] = Math.max(0L, data.tickTimes[i] - data.tickCpuTimes[i]);
            totalTimeTicking += data.tickTimes[i];
        }

        Arrays.sort(tickStartToStartDifferences);
        Arrays.sort(timePerTickDataRaw);
        Arrays.sort(missingCPUTimeDataRaw);

        final int collectedTicks = data.count;
        final int percent95BestEnd = collectedTicks == 1 ? 1 : (int) (0.95 * collectedTicks);
        final int percent99BestEnd = collectedTicks == 1 ? 1 : (int) (0.99 * collectedTicks);
        final int percent1WorstStart = (int) (0.99 * collectedTicks);
        final int percent5WorstStart = (int) (0.95 * collectedTicks);

        final SegmentedAverage tpsData = computeSegmentedAverage(
            tickStartToStartDifferences,
            collectedTicks,
            percent99BestEnd,
            percent95BestEnd,
            percent1WorstStart, collectedTicks,
            percent5WorstStart, collectedTicks,
            true
        );

        final SegmentedAverage timePerTickData = computeSegmentedAverage(
            timePerTickDataRaw,
            collectedTicks,
            percent99BestEnd,
            percent95BestEnd,
            percent1WorstStart, collectedTicks,
            percent5WorstStart, collectedTicks,
            false
        );

        final SegmentedAverage missingCPUTimeData = computeSegmentedAverage(
            missingCPUTimeDataRaw,
            collectedTicks,
            percent99BestEnd,
            percent95BestEnd,
            percent1WorstStart, collectedTicks,
            percent5WorstStart, collectedTicks,
            false
        );

        final double utilisation = (double) totalTimeOverInterval / (double) this.interval;

        return new TickReportData(
            collectedTicks,
            intervalStart,
            intervalEnd,
            totalTimeTicking,
            utilisation,
            tpsData,
            timePerTickData,
            missingCPUTimeData
        );
    }

    private static SegmentedAverage computeSegmentedAverage(
        final long[] data,
        final int allEnd,
        final int percent99BestEnd,
        final int percent95BestEnd,
        final int percent1WorstStart, final int percent1WorstEnd,
        final int percent5WorstStart, final int percent5WorstEnd,
        final boolean inverse
    ) {
        return new SegmentedAverage(
            computeSegmentData(data, 0, allEnd, inverse),
            computeSegmentData(data, 0, percent99BestEnd, inverse),
            computeSegmentData(data, 0, percent95BestEnd, inverse),
            computeSegmentData(data, percent5WorstStart, percent5WorstEnd, inverse),
            computeSegmentData(data, percent1WorstStart, percent1WorstEnd, inverse),
            data
        );
    }

    private static SegmentData computeSegmentData(final long[] arr, final int fromIndex, final int toIndex, final boolean inverse) {
        final int len = toIndex - fromIndex;
        if (len <= 0) throw new IllegalArgumentException("Invalid segment length: " + len);

        long sum = 0L;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;

        // compute median
        long[] segment = Arrays.copyOfRange(arr, fromIndex, toIndex);
        Arrays.sort(segment);
        final int middle = segment.length / 2;
        final double median = (segment.length % 2 == 0)
            ? (segment[middle - 1] + segment[middle]) / 2.0
            : segment[middle];

        // compute sum, min, max
        for (int i = fromIndex; i < toIndex; i++) {
            long val = arr[i];
            sum += val;
            min = Math.min(min, val);
            max = Math.max(max, val);
        }

        if (inverse) {
            // for TPS, invert values
            return new SegmentData(
                len,
                (double) len / ((double) sum / 1.0E9),
                1.0E9 / median,
                1.0E9 / max,
                1.0E9 / min
            );
        } else {
            return new SegmentData(
                len,
                (double) sum / len,
                median,
                min,
                max
            );
        }
    }

    // this is micro-optimisation hell but its an extremely hot function
    private CollapsedData collapseData(final TickTime[] timeData, final TickTime inProgress,
                                       final long tickInterval, final boolean createFakeTick) {
        int idx = 0;
        long totalTickTime = dumbTicksTotalTime;
        long totalCpuTime = dumbTicksTotalCPUTime;
        TickTime lastActualTick = null;

        for (TickTime time : timeData) {
            if (time == null) continue;

            long tickLength = time.tickLength();
            long tickCpu = time.supportCPUTime() ? time.tickCpuTime() : 0L;

            if (!time.isTickExecution()) {
                totalTickTime += tickLength;
                totalCpuTime += tickCpu;
            } else {
                preDiffFromLast[idx] = time.differenceFromLastTick(tickInterval);
                preTickTimes[idx] = tickLength + totalTickTime;
                preTickCpuTimes[idx] = tickCpu + totalCpuTime;
                ++idx;
                totalTickTime = 0L;
                totalCpuTime = 0L;
                lastActualTick = time;
            }
        }

        if (inProgress != null) {
            long tickLength = inProgress.tickLength();
            long tickCpu = inProgress.supportCPUTime() ? inProgress.tickCpuTime() : 0L;

            if (!inProgress.isTickExecution()) {
                totalTickTime += tickLength;
                totalCpuTime += tickCpu;
            } else {
                preDiffFromLast[idx] = inProgress.differenceFromLastTick(tickInterval);
                preTickTimes[idx] = tickLength + totalTickTime;
                preTickCpuTimes[idx] = tickCpu + totalCpuTime;
                ++idx;
                totalTickTime = 0L;
                totalCpuTime = 0L;
                lastActualTick = inProgress;
            }
        }

        if (totalTickTime > 0 && createFakeTick) {
            long diff = lastActualTick != null ? lastActualTick.tickStart() : Math.max(tickInterval, totalTickTime);
            preDiffFromLast[idx] = diff;
            preTickTimes[idx] = totalTickTime;
            preTickCpuTimes[idx] = totalCpuTime;
            ++idx;
        }

        return new CollapsedData(preDiffFromLast, preTickTimes, preTickCpuTimes, idx);
    }

    public record MSPTData(double avg, long[] rawData) {}

    private record CollapsedData(
        long[] diffFromLast,
        long[] tickTimes,
        long[] tickCpuTimes,
        int count
    ) {}

    public record TickReportData(
        int collectedTicks,
        long collectedTickIntervalStart,
        long collectedTickIntervalEnd,
        long totalTimeTicking,
        double utilisation,

        SegmentedAverage tpsData,
        // in ns
        SegmentedAverage timePerTickData,
        // in ns
        SegmentedAverage missingCPUTimeData
    ) {}

    public record SegmentedAverage(
        SegmentData segmentAll,
        SegmentData segment99PercentBest,
        SegmentData segment95PercentBest,
        SegmentData segment5PercentWorst,
        SegmentData segment1PercentWorst,
        long[] rawData
    ) {}

    public record SegmentData(
        int count,
        double average,
        double median,
        double least,
        double greatest
    ) {}
}
