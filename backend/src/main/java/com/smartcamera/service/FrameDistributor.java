package com.smartcamera.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Distributes video frames (NALUs) to multiple subscribers.
 * Each camera can have multiple subscribers (WebSocket clients, FLV streams, etc).
 * Uses a queue-based approach with slow-consumer drop strategy.
 */
@Slf4j
@Service
public class FrameDistributor {

    // cameraId -> list of subscribers
    private final Map<String, List<FrameSubscriber>> subscribers = new ConcurrentHashMap<>();

    /**
     * Subscribe to a camera's video stream.
     */
    public String subscribe(String cameraId, Consumer<byte[]> frameHandler) {
        FrameSubscriber subscriber = new FrameSubscriber(cameraId, frameHandler);
        subscribers.computeIfAbsent(cameraId, k -> new CopyOnWriteArrayList<>());
        subscribers.get(cameraId).add(subscriber);
        subscriber.start();
        log.info("New subscriber for camera {}. Total: {}", cameraId, subscribers.get(cameraId).size());
        return subscriber.getId();
    }

    /**
     * Unsubscribe from a camera's video stream.
     */
    public void unsubscribe(String subscriptionId) {
        for (Map.Entry<String, List<FrameSubscriber>> entry : subscribers.entrySet()) {
            List<FrameSubscriber> subs = entry.getValue();
            subs.removeIf(s -> s.getId().equals(subscriptionId));
            if (subs.isEmpty()) {
                subscribers.remove(entry.getKey());
            }
        }
    }

    /**
     * Distribute a NALU to all subscribers of a camera.
     */
    public void distribute(String cameraId, byte[] nalu) {
        List<FrameSubscriber> subs = subscribers.get(cameraId);
        if (subs == null) return;

        for (FrameSubscriber sub : subs) {
            if (!sub.offer(nalu)) {
                log.debug("Dropped frame for slow subscriber on camera {}", cameraId);
            }
        }
    }

    /**
     * Distribute an audio frame to all subscribers of a camera.
     * Audio frames are tagged and routed alongside video frames.
     */
    public void distributeAudio(String cameraId, byte[] audioFrame) {
        List<FrameSubscriber> subs = subscribers.get(cameraId);
        if (subs == null) return;

        for (FrameSubscriber sub : subs) {
            if (!sub.offer(audioFrame)) {
                log.debug("Dropped audio frame for slow subscriber on camera {}", cameraId);
            }
        }
    }

    /**
     * Clear all subscribers for a camera.
     */
    public void clearCamera(String cameraId) {
        List<FrameSubscriber> subs = subscribers.remove(cameraId);
        if (subs != null) {
            for (FrameSubscriber sub : subs) {
                sub.stop();
            }
        }
    }

    /**
     * Individual subscriber with its own queue and dispatch thread.
     */
    private static class FrameSubscriber {
        private final String id;
        private final String cameraId;
        private final Consumer<byte[]> frameHandler;
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(64); // max 64 frames buffered
        private volatile boolean running = true;
        private Thread dispatchThread;

        FrameSubscriber(String cameraId, Consumer<byte[]> frameHandler) {
            this.id = cameraId + "_" + System.nanoTime();
            this.cameraId = cameraId;
            this.frameHandler = frameHandler;
        }

        public String getId() {
            return id;
        }

        boolean offer(byte[] nalu) {
            return queue.offer(nalu);
        }

        void start() {
            dispatchThread = new Thread(() -> {
                while (running) {
                    try {
                        byte[] nalu = queue.poll(1, TimeUnit.SECONDS);
                        if (nalu != null) {
                            frameHandler.accept(nalu);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.error("Error in frame dispatch for subscriber {}", id, e);
                    }
                }
            }, "frame-dispatch-" + id);
            dispatchThread.setDaemon(true);
            dispatchThread.start();
        }

        void stop() {
            running = false;
            if (dispatchThread != null) {
                dispatchThread.interrupt();
            }
        }
    }
}
