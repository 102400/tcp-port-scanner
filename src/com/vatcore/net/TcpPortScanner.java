package com.vatcore.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class TcpPortScanner implements Closeable {

    private static final Object LOCK = new Object();  // 用于构造和销毁

    public static class Config {
        public static final Config IMPL = new Config();
        private int MAX_CONNECTIONS = 42000;
        private int FINISH_PARALLELISM = Runtime.getRuntime().availableProcessors() * 2;
        public Config setMaxConnections(int maxConnections) {
            this.MAX_CONNECTIONS = maxConnections;
            return IMPL;
        }
        public Config setFinishParallelism(int parallelism) {
            this.FINISH_PARALLELISM = parallelism;
            return IMPL;
        }
    }

    private static final int MAX_CONNECTIONS = Config.IMPL.MAX_CONNECTIONS;
    private static final Semaphore SEMAPHORE = new Semaphore(MAX_CONNECTIONS);  // 最大连接数
    private static final ExecutorService FINISH_EXECUTOR = Executors.newWorkStealingPool(Config.IMPL.FINISH_PARALLELISM);

    private static Selector SELECTOR;
    private static final Exchanger<Object> START_SELECT_EXCHANGER = new Exchanger<>();

    // queueId -> value
    private static final BlockingQueue<Map<String, InetSocketAddress>> CONNECT_QUEUE = new LinkedBlockingQueue<>();
    private static final Map<String, BlockingQueue<InetSocketAddress>> OK_QUEUE_MAP = new ConcurrentHashMap<>();

    private static final Map<String, AtomicLong> ACCEPT_TOTAL_MAP = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> FINISH_TOTAL_MAP = new ConcurrentHashMap<>();

    private final String queueId;

    private final BlockingQueue<InetSocketAddress> okQueue = new LinkedBlockingQueue<>();  // 成功队列

    private final AtomicLong acceptTotal = new AtomicLong(0);  // 总共接受了几个请求连接
    private final AtomicLong finishTotal = new AtomicLong(0);  // 总共结束了几个连接

    private boolean isOffered = false;
    private boolean isClosed = false;

    static {
        try {
            System.out.println("static...");
            SELECTOR = Selector.open();
            startWorker();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static int getMaxConnections() {
        return MAX_CONNECTIONS;
    }

    /**
     * 可用的连接数
     */
    public static int availableConnections() {
        return SEMAPHORE.availablePermits();
    }

    public TcpPortScanner() {}

    {
        synchronized (LOCK) {
            queueId = UUID.randomUUID().toString();
            OK_QUEUE_MAP.put(queueId, okQueue);  // 注册到成功队列里
            ACCEPT_TOTAL_MAP.put(queueId, acceptTotal);
            FINISH_TOTAL_MAP.put(queueId, finishTotal);
        }
    }

    private static void startWorker() {
        Thread workerThread = new Thread(() -> {
            Thread connectWorker = null,
                    selectWorker = null;
            try {
                connectWorker = startConnectWorker();
                START_SELECT_EXCHANGER.exchange(null);  // 阻塞等待, 直到有一个connect请求
                selectWorker = startSelectWorker();
            } catch (InterruptedException e) {
//                if (connectWorker != null) connectWorker.interrupt();
//                if (selectWorker != null) selectWorker.interrupt();
            }
        });
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private static Thread startConnectWorker() {
        Thread thread = new Thread(() -> {
            boolean startSelect = true;
            while (true) {
                try {
                    SocketChannel[] socketChannel = {null};
                    SEMAPHORE.acquire();
                    try {
                        Map<String, InetSocketAddress> map = CONNECT_QUEUE.take();
                        Map.Entry<String, InetSocketAddress> entry = map.entrySet().iterator().next();

                        socketChannel[0] = SocketChannel.open();
                        socketChannel[0].configureBlocking(false);
                        socketChannel[0].register(SELECTOR, SelectionKey.OP_CONNECT, entry);
                        socketChannel[0].connect(entry.getValue());

                        if (startSelect) {
                            START_SELECT_EXCHANGER.exchange(null);
                            startSelect = false;
                        }
                        Optional.ofNullable(ACCEPT_TOTAL_MAP.get(entry.getKey())).ifPresent(AtomicLong::incrementAndGet);
                    } catch (IOException | InterruptedException e) {
                        SEMAPHORE.release();
                        if (socketChannel[0] != null) {
                            try {
                                socketChannel[0].finishConnect();
                            } catch (IOException e1) {
                                // ...
                            }
                        }
                    }
                } catch (InterruptedException e1) {
                    // ...
                }
            }
        });
        thread.start();
        return thread;
    }

    private static Thread startSelectWorker() {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    SELECTOR.select();

                    Iterator<SelectionKey> iterator = SELECTOR.selectedKeys().iterator();
                    while (iterator.hasNext()) {
                        SelectionKey key = iterator.next();
                        iterator.remove();
                        key.cancel();

                        SocketChannel socketChannel = (SocketChannel) key.channel();

                        FINISH_EXECUTOR.execute(() -> {
                            String targetQueueId = null;
                            try {
                                // queueId -> value
                                Map.Entry<String, InetSocketAddress> entry = (Map.Entry<String, InetSocketAddress>) key.attachment();
                                targetQueueId = entry.getKey();

                                socketChannel.finishConnect();  // 尝试结束连接... 不能结束会抛异常

                                Optional.ofNullable(OK_QUEUE_MAP.get(entry.getKey()))
                                        .ifPresent(blockingQueue -> blockingQueue.offer(entry.getValue()));
                            }
                            catch (IOException e) {
                                // ...
                            }
                            finally {
                                SEMAPHORE.release();
                                if (targetQueueId != null) Optional.ofNullable(FINISH_TOTAL_MAP.get(targetQueueId)).ifPresent(AtomicLong::incrementAndGet);
                            }
                        });
                    }
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        thread.start();
        return thread;
    }

    public boolean offer(InetSocketAddress inetSocketAddress) {
        if (!isOffered) isOffered = true;
        return CONNECT_QUEUE.offer(Collections.singletonMap(queueId, inetSocketAddress));
    }

    public InetSocketAddress take() throws InterruptedException {
        return okQueue.take();
    }

    public long getAcceptTotal() {
        return acceptTotal.longValue();
    }

    public long getFinishTotal() {
        return finishTotal.longValue();
    }

    public boolean isFinished() {
        return isClosed || (isOffered && acceptTotal.longValue() == finishTotal.longValue());
    }

    @Override
    public void close() throws IOException {
        synchronized (LOCK) {
            OK_QUEUE_MAP.remove(queueId);
            ACCEPT_TOTAL_MAP.remove(queueId);
            FINISH_TOTAL_MAP.remove(queueId);
            isClosed = true;
        }
    }

}