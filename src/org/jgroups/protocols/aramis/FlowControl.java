package org.jgroups.protocols.aramis;

import org.jgroups.Message;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.util.Util;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class provides a bespoke FlowControl mechanism for Aramis and Base that utilises the values collected by Aramis's
 * NMC to provide a means of flow control.
 *
 * @author Ryan Emerson
 * @since 4.0
 */
public class FlowControl {

    private final int BUCKET_SIZE = 1;
    private final double DELTA_UPPER_LIMIT = 0.01; // The max value of delta in seconds e.g. 0.01 = 10ms
    private final double DELTA_LOWER_LIMIT = 0.001; // The min value of delta in seconds
    private final ReentrantLock lock = new ReentrantLock(false);
    private final Condition condition = lock.newCondition();
    private final AtomicInteger bucketId = new AtomicInteger();
    private final Log log = LogFactory.getLog(Aramis.class);
    private final NMC nmc;
    private final Aramis aramis;

    private BucketWrapper buckets = new BucketWrapper();
    private FCDataWrapper flowData = new FCDataWrapper();
    private NMCData nmcData = null; // The most recent nmc data accessed by this object

    private final Profiler profiler = new Profiler();
    private final boolean PROFILE_ENABLED = false;

    public FlowControl(Aramis aramis, NMC nmc) {
        this.aramis = aramis;
        this.nmc = nmc;

        if (PROFILE_ENABLED) {
            // TODO remove
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    System.out.println("Flow Control -------\n" + profiler);
                }
            }));
        }
    }

    public void addMessage(Message message) {
        lock.lock();
        try {
            MessageBucket bucket = buckets.current;
            boolean bucketIsFull = bucket.addMessage(message);
            if (bucketIsFull) {
                try {
                    bucket.delay();
                    bucket.send();
                    bucket.clear();
                } catch (InterruptedException e) {
                    if (log.isDebugEnabled())
                        log.debug("Delay Exception: " + e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private class MessageBucket {
        final int id;
        final Message[] messages;
        volatile int messageIndex = 0;
        volatile long broadcastTime = -1;
        volatile boolean sent = false;
        volatile MessageBucket previous = null;
        volatile long actualSendTime = -1;

        public MessageBucket() {
            id = bucketId.getAndIncrement();
            messages = new Message[BUCKET_SIZE];
        }

        boolean addMessage(Message message) {
            messages[messageIndex++] = message;

            if (isFull()) {
                boolean messageSent = false;
                while (!messageSent) {
                    try {
                        calculateBroadcastRate();
                        calculateBroadcastTime();
                        messageSent = true;
                    } catch (IOException e) {
                        if (log.isInfoEnabled())
                            log.info("Exception thrown: " + e + "\n retry message in 1 ms");
                        Util.sleep(1);
                    }
                }

                buckets.cycle();
                return true;
            }
            return false;
        }

        boolean isFull() {
            return messageIndex == BUCKET_SIZE;
        }

        void calculateBroadcastRate() {
            if (buckets.oldest != null && buckets.previous != null)
                flowData.broadcastRate = 1e+9 / ((double) (buckets.previous.broadcastTime - buckets.oldest.broadcastTime) / BUCKET_SIZE); // Number of messages broadcast per second
            else
                flowData.broadcastRate = 0.0;
        }

        void calculateBroadcastTime() throws IOException {
            boolean newDelta = calculateDelta();
            double bucketDelay = newDelta ? flowData.delta * BUCKET_SIZE : flowData.bucketDelay;

            if (bucketDelay > DELTA_UPPER_LIMIT) {
                if (PROFILE_ENABLED)
                    profiler.deltaLimitExceeded(bucketDelay);
                bucketDelay = DELTA_UPPER_LIMIT; // Reset the buckets delay to the upper limit
            }

            if (bucketDelay < DELTA_LOWER_LIMIT)
                bucketDelay = DELTA_LOWER_LIMIT;

            double delay = bucketDelay;
            long delayInNanos = delay == 0 ? 0 : (long) Math.ceil(delay * 1e+9); // Convert to nanoseconds * 1e+9 so that the delay can be added to the currentTime

            if (buckets.previous != null) {
                broadcastTime = buckets.previous.broadcastTime + delayInNanos;
                previous = buckets.previous;
            } else
                broadcastTime = aramis.getClock().getTime() + delayInNanos;

            flowData.bucketDelay = bucketDelay;
        }

        // returns true if a new delta value is calculated, false if the old value is still relevant
        boolean calculateDelta() throws IOException {
            NMCData newNMCData = nmc.getData();
            if (newNMCData == null)
                throw new IOException("NMCData returned by nmc.getData() is null.  Initial probe period not complete");

            if (newNMCData.equals(nmcData)) {
                try {
                    // If the exponential result is different to the previous then it means that the number of latencies
                    // that have exceeded xMax has increased (can't decrease because xMax would have changed)
                    double exponentialResult = getExponentialResult();
                    if (exponentialResult != flowData.exponentialResult) {

                        // Necessary for the first bucket, prevents delta == infinity
                        if (flowData.broadcastRate == 0)
                            flowData.delta = 0;
                        else
                            flowData.delta = (1 / flowData.broadcastRate) * ((1 - exponentialResult) / exponentialResult);

                        flowData.exponentialResult = exponentialResult;
                        return true;
                    }
                } catch (Exception e) {
                    // If an exception is thrown by getExponentialResult then it means no latencies have exceeded Xrc
                }
                return false; // The old delta value will be used
            } else {
                flowData.delta = DELTA_LOWER_LIMIT;
                nmcData = newNMCData;
            }
            return true;
        }

        double getExponentialResult() throws Exception {
            double r = nmc.calculateR();
            int c = 1; // TODO make configurable

            // return the new broadcast rate (omega2)
            return Math.pow(Math.E, ((1 - r) / c));
        }

        void send() {
            actualSendTime = aramis.getClock().getTime();
            profiler.msgCount++;

            if (previous != null)
                profiler.delayTotal += actualSendTime - previous.actualSendTime;

            for (Message message : messages)
                aramis.sendRMCast(message);

            sent = true;
            condition.signalAll();
        }

        public void delay() throws InterruptedException {
            long delay;
            while ((delay = getDelay()) > 0)
                condition.awaitNanos(delay);

            while (previous != null && !previous.sent)
                condition.await();
        }

        public long getDelay() {
            long delay = broadcastTime - aramis.getClock().getTime();
            return delay < 0 ? 0 : delay;
        }

        // Necessary for garbage collection
        void clear() {
            previous = null;
        }

        @Override
        public String toString() {
            return "MessageBucket{" +
                    "id=" + id +
                    ", messageIndex=" + messageIndex +
                    ", broadcastTime=" + broadcastTime +
                    '}';
        }
    }

    private class FCDataWrapper {
        double delta = 0.0;
        double broadcastRate = 0.0;
        double exponentialResult = 0.0;
        double bucketDelay = 0.0;

        @Override
        public String toString() {
            return "FCDataWrapper{" +
                    ", delta=" + delta +
                    ", broadcastRate=" + broadcastRate +
                    ", exponentialResult=" + exponentialResult +
                    ", bucketDelay=" + bucketDelay +
                    '}';
        }
    }

    private class BucketWrapper {
        MessageBucket current;
        MessageBucket previous;
        MessageBucket oldest;

        public BucketWrapper() {
            current = new MessageBucket();
        }

        void cycle() {
            oldest = previous;
            previous = current;
            current = new MessageBucket();
        }
    }

    private class Profiler {
        int deltaExceeded = 0;
        double deltaExceededTotal = 0;
        double deltaHighest = Double.MIN_VALUE;
        double deltaLowest = Double.MAX_VALUE;
        int cumulativeLimit = 1; // 1 Second
        int cumulativeExceeded = 0;
        double cumulativeExceededTotal = 0;
        long delayTotal = 0;
        int msgCount = 0;

        public void deltaLimitExceeded(double bucketDelay) {
            deltaExceeded++;
            deltaHighest = Math.max(profiler.deltaHighest, bucketDelay);
            deltaLowest = Math.min(profiler.deltaLowest, bucketDelay);
            deltaExceededTotal += bucketDelay - DELTA_UPPER_LIMIT;
        }

        @Override
        public String toString() {
            return "Profiler{" +
                    "Delta Exceeded=" + deltaExceeded + "\n" +
                    ", Delta Exceeded Average =" + (deltaExceeded > 0 ? (deltaExceededTotal / deltaExceeded) : 0) + "\n" +
                    ", Delta Highest =" + (deltaHighest - DELTA_UPPER_LIMIT) + "\n" +
                    ", Delta Lowest =" + (deltaLowest - DELTA_UPPER_LIMIT) + "\n" +
                    ", w Limit=" + cumulativeLimit + "\n" +
                    ", w Exceeded=" + cumulativeExceeded + "\n" +
                    ", w Average=" + cumulativeExceededTotal + "\n" +
                    ", Delay Average=" + (msgCount > 0 ? (delayTotal / msgCount) : 0) + "ns" + "\n" +
                    ", Msg Count=" + msgCount + "\n" +
                    ", delayTotal =" + delayTotal +
                    '}';
        }
    }
}