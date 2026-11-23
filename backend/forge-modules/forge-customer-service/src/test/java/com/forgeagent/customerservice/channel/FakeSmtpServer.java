package com.forgeagent.customerservice.channel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A-4:最小 SMTP 假服务端(零新增依赖),只回答 Jakarta Mail 默认 SMTP 握手所需命令。
 *
 * <p>刻意只实现 send 方向(EHLO/AUTH/MAIL/RCPT/DATA/QUIT),不碰 IMAP;
 * 用 {@code ServerSocket(0)} 动态取端口,避免测试间端口抢占。
 * 收到的命令与 DATA 载荷分别入队,供测试断言真实发出去的报文。</p>
 */
final class FakeSmtpServer implements AutoCloseable {

    private static final Duration IO_TIMEOUT = Duration.ofSeconds(10);

    private final ServerSocket server;
    private final Thread worker;
    private final BlockingQueue<String> commands = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> payloads = new LinkedBlockingQueue<>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile boolean closing = false;

    FakeSmtpServer() throws Exception {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        server.setSoTimeout((int) IO_TIMEOUT.toMillis());
        worker = new Thread(this::serve, "fake-smtp-" + server.getLocalPort());
        worker.setDaemon(true);
        worker.start();
    }

    int port() {
        return server.getLocalPort();
    }

    private void serve() {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout((int) IO_TIMEOUT.toMillis());
            BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1));
            reply(out, "220 localhost ESMTP fake");

            boolean inData = false;
            StringBuilder payload = new StringBuilder();
            String line;
            while (!closing && (line = in.readLine()) != null) {
                if (inData) {
                    if (".".equals(line)) {
                        payloads.offer(payload.toString());
                        payload.setLength(0);
                        inData = false;
                        reply(out, "250 OK: queued");
                    } else {
                        payload.append(line).append("\r\n");
                    }
                    continue;
                }
                commands.offer(line);
                String cmd = line.toUpperCase(Locale.ROOT);
                if (cmd.startsWith("EHLO") || cmd.startsWith("HELO")) {
                    // 末行必须 "250 " 而非 "250-",否则客户端认为能力列表未结束
                    reply(out, "250-localhost", "250-AUTH PLAIN LOGIN", "250 8BITMIME");
                } else if (cmd.startsWith("AUTH")) {
                    reply(out, "235 2.7.0 Authentication successful");
                } else if (cmd.startsWith("DATA")) {
                    reply(out, "354 End data with <CR><LF>.<CR><LF>");
                    inData = true;
                } else if (cmd.startsWith("QUIT")) {
                    reply(out, "221 Bye");
                    break;
                } else {
                    reply(out, "250 OK");
                }
            }
        } catch (SocketTimeoutException e) {
            commands.offer("[TIMEOUT]");
        } catch (Exception e) {
            if (!closing) {
                commands.offer("[ERROR] " + e.getMessage());
            }
        } finally {
            stopped.countDown();
        }
    }

    private static void reply(BufferedWriter out, String... lines) throws Exception {
        for (String line : lines) {
            out.write(line + "\r\n");
        }
        out.flush();
    }

    /** 等待包含指定前缀的命令;超时返回 null,便于测试给出可读断言失败信息。 */
    String awaitCommand(String prefix, Duration timeout) throws InterruptedException {
        String wanted = prefix.toUpperCase(Locale.ROOT);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            for (String c : commands) {
                if (c.toUpperCase(Locale.ROOT).startsWith(wanted)) {
                    return c;
                }
            }
            long remain = deadline - System.nanoTime();
            if (remain <= 0) {
                return null;
            }
            Thread.sleep(10);
        }
    }

    String awaitPayload(Duration timeout) throws InterruptedException {
        return payloads.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** 诊断用:返回已收到的全部命令快照。 */
    List<String> commands() {
        return new ArrayList<>(commands);
    }

    @Override
    public void close() throws Exception {
        closing = true;
        try {
            server.close();
        } catch (Exception ignored) {
            // 已关闭
        }
        stopped.await(2, TimeUnit.SECONDS);
    }
}
