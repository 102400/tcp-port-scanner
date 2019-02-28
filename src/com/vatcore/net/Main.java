package com.vatcore.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public class Main {

    static {
//        TcpPortScanner.Config.IMPL.setMaxConnections(3600);
//                .setFinishParallelism(32);
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        System.out.println(new Date());
        final long start = System.currentTimeMillis();

        TcpPortScanner tcpPortScanner = new TcpPortScanner();

        final Thread okThread = new Thread(() -> {
            while (true) {
                try {
                    System.out.println(tcpPortScanner.take());  // get ok result
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        okThread.setDaemon(true);
        okThread.start();

        for (int x = 0; x < 1; x++) {
            for (int i = 0; i <= 255; i++) {
                for (int j = 0; j <= 255; j++) {
                    tcpPortScanner.offer(new InetSocketAddress("172.93." + i + "." + j, 80));  // offer address
                }
            }
        }

        new Thread(() -> {
            for (int i =0; i < 1000 && !tcpPortScanner.isFinished(); i++) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                    System.out.println(i + " >>> "
                            + tcpPortScanner.getAcceptTotal() + ":" + tcpPortScanner.getFinishTotal() + " >>> "
                            + TcpPortScanner.availableConnections()
                    );
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        while (true) {
            if (tcpPortScanner.isFinished()) {
                System.out.println(">>> " + (System.currentTimeMillis() - start) + " ms");
                break;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
    }
}